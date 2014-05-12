/**
 * Copyright (c) 2011 Yahoo! Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. See accompanying LICENSE file.
 */

package com.yahoo.omid.transaction;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.util.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;
import com.yahoo.omid.client.TSOClient;
import com.yahoo.omid.client.TSOClient.AbortException;
import com.yahoo.omid.transaction.Transaction.Status;

/**
 * Provides the methods necessary to create and commit transactions.
 * 
 * @see TTable
 * 
 */
public class TransactionManager {

    private static final byte[] SHADOW_CELL_SUFFIX = ":OMID_CTS".getBytes(Charsets.UTF_8);

    private static final Logger LOG = LoggerFactory.getLogger(TransactionManager.class);

    private TSOClient tsoclient = null;
    private Configuration conf;
    private HashMap<byte[], HTableInterface> tableCache;
    private HTableFactory hTableFactory;

    public static class Builder {
        Configuration conf = new Configuration();
        HTableFactory htableFactory = new DefaultHTableFactory();
        TSOClient tsoClient = null;

        private Builder() {}

        public Builder withConfiguration(Configuration conf) {
            this.conf = conf;
            return this;
        }

        Builder withHTableFactory(HTableFactory htableFactory) {
            this.htableFactory = htableFactory;
            return this;
        }

        public Builder withTSOClient(TSOClient tsoClient) {
            this.tsoClient = tsoClient;
            return this;
        }

        public TransactionManager build() throws IOException {
            if (tsoClient == null) {
                tsoClient = TSOClient.newBuilder()
                    .withConfiguration(convertToCommonsConf(conf)).build();
            }
            return new TransactionManager(conf, htableFactory, tsoClient);
        }
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    private TransactionManager(Configuration conf, HTableFactory hTableFactory, TSOClient tsoClient)
            throws IOException {
        this.conf = conf;
        this.hTableFactory = hTableFactory;
        tableCache = new HashMap<byte[], HTableInterface>();
        this.tsoclient = tsoClient;
    }

    static org.apache.commons.configuration.Configuration convertToCommonsConf(Configuration hconf) {
        org.apache.commons.configuration.Configuration conf
            = new org.apache.commons.configuration.BaseConfiguration();
        for (Map.Entry<String,String> e : hconf) {
            conf.addProperty(e.getKey(), e.getValue());            
        }
        return conf;
    }

    /**
     * Starts a new transaction.
     * 
     * This method returns an opaque {@link Transaction} object, used by
     * {@link TTable}'s methods for performing operations on a given
     * transaction.
     * 
     * @return Opaque object which identifies one transaction.
     * @throws TransactionException
     */
    public Transaction begin() throws TransactionException {
        try {
            long startTimestamp = tsoclient.createTransaction().get();
            return new Transaction(startTimestamp, tsoclient);
        } catch (ExecutionException e) {
            throw new TransactionException("Could not get new timestamp", e);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new TransactionException("Interrupted getting timestamp", ie);
        }
    }

    /**
     * Commits a transaction. If the transaction is aborted it automatically
     * rollbacks the changes and throws a {@link RollbackException}.
     * 
     * @param transaction
     *            Object identifying the transaction to be committed.
     * @throws RollbackException
     * @throws TransactionException
     */
    public void commit(Transaction transaction) throws RollbackException, TransactionException {
        if (transaction.getStatus() != Status.RUNNING) {
            throw new IllegalArgumentException("Transaction has already been " + transaction.getStatus());
        }

        // Check rollbackOnly status
        if (transaction.isRollbackOnly()) {
            rollback(transaction);
            throw new RollbackException();
        }

        // Flush all pending writes
        if (!flushTables(transaction)) {
            cleanup(transaction);
            throw new RollbackException();
        }

        Future<Long> commit = tsoclient.commit(transaction.getStartTimestamp(),
                                               transaction.getCells());
        try {
            long commitTs = commit.get();
            transaction.setStatus(Status.COMMITTED);
            transaction.setCommitTimestamp(commitTs);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof AbortException) {
                cleanup(transaction);
                throw new RollbackException();
            }
            throw new TransactionException("Could not commit", e.getCause());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new TransactionException("Interrupted committing transaction", ie);
        }
        postCommit(transaction);
    }

    void postCommit(Transaction transaction) {
        Set<HBaseCellIdImpl> cells = transaction.getCells();

        boolean failureOccurred = false;

        // Add shadow cells
        for(HBaseCellIdImpl cell : cells) {
            Put put = new Put(cell.getRow());
            put.add(cell.getFamily(), addShadowCellSuffix(cell.getQualifier()),
                    transaction.getStartTimestamp(), Bytes.toBytes(transaction.getCommitTimestamp()));
            try {
                cell.getTable().put(put);
            } catch (IOException e) {
                failureOccurred = true;
                LOG.warn("Failed inserting shadow cell {} for Tx {}", new Object[] { cell, transaction, e });
            }
        }
        // Remove transaction from commit table in not failure occurred
        if(!failureOccurred) {
            tsoclient.completeTransaction(transaction.getStartTimestamp());
        }

    }

    public static byte[] addShadowCellSuffix(byte[] qualifier) {
        return com.google.common.primitives.Bytes.concat(qualifier, SHADOW_CELL_SUFFIX);
    }

    public static boolean isShadowCell(byte[] qualifier) {
        int index = com.google.common.primitives.Bytes.indexOf(qualifier, SHADOW_CELL_SUFFIX);
        return index >= 0 && index == (qualifier.length - SHADOW_CELL_SUFFIX.length);
    }

    /**
     * Flushes pending operations for tables touched by transaction 
     * @param transaction
     * @return true if the flush operations succeeded, false otherwise
     */
    private boolean flushTables(Transaction transaction) {
        boolean result = true;
        for (HTableInterface writtenTable : transaction.getWrittenTables()) {
            try {
                writtenTable.flushCommits();
            } catch (IOException e) {
                LOG.error("Exception while flushing writes", e);
                result = false;
            }
        }
        return result;
    }

    /**
     * Aborts a transaction and automatically rollbacks the changes.
     * 
     * @param transaction
     *            Object identifying the transaction to be committed.
     */
    public void rollback(Transaction transaction) {
        if (transaction.getStatus() != Status.RUNNING) {
            throw new IllegalStateException("Transaction has already been " + transaction.getStatus());
        }

        flushTables(transaction);

        // Make sure its commit timestamp is 0, so the cleanup does the right job
        transaction.setCommitTimestamp(0);
        cleanup(transaction);
    }

    private void cleanup(final Transaction transaction) {
        transaction.setStatus(Status.ABORTED);

        Map<byte[], List<Delete>> deleteBatches = new HashMap<byte[], List<Delete>>();
        for (final HBaseCellIdImpl cell : transaction.getCells()) {
            Delete delete = new Delete(cell.getRow());
            delete.deleteColumn(cell.getFamily(), cell.getQualifier(), transaction.getStartTimestamp());
            try {
                cell.getTable().delete(delete);
            } catch (IOException e) {
                LOG.warn("Failed cleanup cell {} for Tx {}", new Object[] { cell, transaction, e });
            }
        }

    }
}