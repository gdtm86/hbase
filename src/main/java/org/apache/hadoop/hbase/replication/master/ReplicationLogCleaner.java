/*
 * Copyright 2010 The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.replication.master;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.master.HMaster;
import org.apache.hadoop.hbase.master.LogCleanerDelegate;
import org.apache.hadoop.hbase.master.TimeToLiveLogCleaner;
import org.apache.hadoop.hbase.replication.ReplicationZookeeperWrapper;
import org.apache.hadoop.hbase.zookeeper.ZooKeeperWrapper;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Implementation of a log cleaner that checks if a log is still scheduled for
 * replication before deleting it when its TTL is over.
 */
@Deprecated
public class ReplicationLogCleaner implements LogCleanerDelegate, Watcher {

  private static final Log LOG =
      LogFactory.getLog(ReplicationLogCleaner.class);
  private TimeToLiveLogCleaner ttlCleaner;
  private Configuration conf;
  private ReplicationZookeeperWrapper zkHelper;
  private Set<String> hlogs = new HashSet<String>();
  private final String zkWrapperName;

  /**
   * Instantiates the cleaner, does nothing more.
   */
  public ReplicationLogCleaner(String zkWrapperName) {
    this.zkWrapperName = zkWrapperName;
  }

  @Override
  public boolean isLogDeletable(Path filePath) {

    // Don't bother going further if the hlog isn't even expired
    if (!ttlCleaner.isLogDeletable(filePath)) {
      LOG.debug("Won't delete log since not past due " + filePath);
      return false;
    }
    String log = filePath.getName();
    // If we saw the hlog previously, let's consider it's still used
    // At some point in the future we will refresh the list and it will be gone
    if (this.hlogs.contains(log)) {
      return false;
    }

    // Let's see it's still there
    // This solution makes every miss very expensive to process since we
    // almost completly refresh the cache each time
    return !refreshHLogsAndSearch(log);
  }

  /**
   * Search through all the hlogs we have in ZK to refresh the cache
   * If a log is specified and found, then we early out and return true
   * @param searchedLog log we are searching for, pass null to cache everything
   *                    that's in zookeeper.
   * @return false until a specified log is found.
   */
  private boolean refreshHLogsAndSearch(String searchedLog) {
    this.hlogs.clear();
    final boolean lookForLog = searchedLog != null;
    List<String> rss = zkHelper.getListOfReplicators(this);
    if (rss == null) {
      LOG.debug("Didn't find any region server that replicates, deleting: " +
          searchedLog);
      return false;
    }
    for (String rs: rss) {
      List<String> listOfPeers = zkHelper.getListPeersForRS(rs, this);
      // if rs just died, this will be null
      if (listOfPeers == null) {
        continue;
      }
      for (String id : listOfPeers) {
        List<String> peersHlogs = zkHelper.getListHLogsForPeerForRS(rs, id, this);
        if (peersHlogs != null) {
          this.hlogs.addAll(peersHlogs);
        }
        // early exit if we found the log
        if(lookForLog && this.hlogs.contains(searchedLog)) {
          LOG.debug("Found log in ZK, keeping: " + searchedLog);
          return true;
        }
      }
    }
    LOG.debug("Didn't find this log in ZK, deleting: " + searchedLog);
    return false;
  }

  @Override
  public void setConf(Configuration conf) {
    this.conf = conf;
    this.ttlCleaner = new TimeToLiveLogCleaner();
    this.ttlCleaner.setConf(conf);
    try {
      this.zkHelper = new ReplicationZookeeperWrapper(
          ZooKeeperWrapper.createInstance(this.conf, zkWrapperName),
          this.conf, new AtomicBoolean(true), null);
    } catch (IOException e) {
      LOG.error(e);
    }
    refreshHLogsAndSearch(null);
  }

  @Override
  public Configuration getConf() {
    return conf;
  }

  @Override
  public void process(WatchedEvent watchedEvent) {}
}
