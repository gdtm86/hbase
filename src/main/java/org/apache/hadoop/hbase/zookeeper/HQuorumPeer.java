/**
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
package org.apache.hadoop.hbase.zookeeper;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.net.DNS;
import org.apache.hadoop.util.StringUtils;
import org.apache.zookeeper.server.ServerConfig;
import org.apache.zookeeper.server.ZooKeeperServerMain;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig;
import org.apache.zookeeper.server.quorum.QuorumPeerMain;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map.Entry;
import java.util.Properties;

/**
 * HBase's version of ZooKeeper's QuorumPeer. When HBase is set to manage
 * ZooKeeper, this class is used to start up QuorumPeer instances. By doing
 * things in here rather than directly calling to ZooKeeper, we have more
 * control over the process. Currently, this class allows us to parse the
 * zoo.cfg and inject variables from HBase's site.xml configuration in.
 */
public class HQuorumPeer {
  private static final Log LOG = LogFactory.getLog(HQuorumPeer.class);

  private static final String VARIABLE_START = "${";
  private static final int VARIABLE_START_LENGTH = VARIABLE_START.length();
  private static final String VARIABLE_END = "}";
  private static final int VARIABLE_END_LENGTH = VARIABLE_END.length();

  private static final int ZK_CLIENT_PORT_KEY_PREFIX_LENGTH =
      HConstants.ZK_CFG_PROPERTY_PREFIX.length();

  /**
   * Parse ZooKeeper configuration from HBase XML config and run a QuorumPeer.
   * @param args String[] of command line arguments. Not used.
   */
  public static void main(String[] args) {
    Configuration conf = HBaseConfiguration.create();
    try {
      Properties zkProperties = makeZKProps(conf);
      writeMyID(zkProperties);
      HQuorumPeerConfig zkConfig = new HQuorumPeerConfig();
      zkConfig.parseProperties(zkProperties);
      runZKServer(zkConfig);
    } catch (Exception e) {
      e.printStackTrace();
      System.exit(-1);
    }
  }

  private static void runZKServer(QuorumPeerConfig zkConfig) throws UnknownHostException, IOException {
    if (zkConfig.isDistributed()) {
      QuorumPeerMain qp = new QuorumPeerMain();
      qp.runFromConfig(zkConfig);
    } else {
      ZooKeeperServerMain zk = new ZooKeeperServerMain();
      ServerConfig serverConfig = new ServerConfig();
      serverConfig.readFrom(zkConfig);
      zk.runFromConfig(serverConfig);
    }
  }

  private static boolean addressIsLocalHost(String address) {
    // localhost address for IPv4 and IPv6 addresses
    return address.equals("localhost") || address.equals("127.0.0.1") ||
        address.equals("localhost6") || address.equals("::1");
  }

  static void writeMyID(Properties properties) throws IOException {
    long myId = -1;

    Configuration conf = HBaseConfiguration.create();
    String myAddress = DNS.getDefaultHost(
        conf.get("hbase.zookeeper.dns.interface","default"),
        conf.get("hbase.zookeeper.dns.nameserver","default"));

    List<String> ips = new ArrayList<String>();

    // Add what could be the best (configured) match
    ips.add(myAddress.contains(".") ?
        myAddress :
        StringUtils.simpleHostname(myAddress));

    // For all nics get all hostnames and IPs
    Enumeration<?> nics = NetworkInterface.getNetworkInterfaces();
    while(nics.hasMoreElements()) {
      Enumeration<?> rawAdrs =
          ((NetworkInterface)nics.nextElement()).getInetAddresses();
      while(rawAdrs.hasMoreElements()) {
        InetAddress inet = (InetAddress) rawAdrs.nextElement();
        ips.add(StringUtils.simpleHostname(inet.getHostName()));
        String hostAddr = inet.getHostAddress();
        ips.add(hostAddr);
        // Add the global IPv6 address without the scope id part
        if (hostAddr.contains(":") && hostAddr.contains("%")) {
          ips.add(hostAddr.substring(0, hostAddr.indexOf("%")));
        }
      }
    }

    for (Entry<Object, Object> entry : properties.entrySet()) {
      String key = entry.getKey().toString().trim();
      String value = entry.getValue().toString().trim();
      if (key.startsWith("server.")) {
        int dot = key.indexOf('.');
        long id = Long.parseLong(key.substring(dot + 1));
        /* According to trunk version of QuorumPeerConfig$parseProperties
         * Treat an address contained with [ ] as an IPv6 address if it
         * contains only hex digits and colons. IPv6 addresses will
         * recognized only if specified in this format.
         */
        boolean ipv6 = value.matches("\\[[0-9a-fA-F:]*\\].*");
        String parts[];
        if (ipv6) {
          String blocks[] = value.split("]");
          String ipv6Address = blocks[0].substring(1);
          parts = blocks[1].split(":");
          // The first element in "parts" should be the IP address.
          parts[0] = ipv6Address;
        } else {
          parts = value.split(":");
        }
        String address = parts[0];

        if (addressIsLocalHost(address) || ips.contains(address)) {
          myId = id;
          break;
        }
      }
    }

    if (myId == -1) {
      throw new IOException("Could not find my address: " + myAddress +
                            " in list of ZooKeeper quorum servers");
    }

    String dataDirStr = properties.get("dataDir").toString().trim();
    File dataDir = new File(dataDirStr);
    if (!dataDir.isDirectory()) {
      if (!dataDir.mkdirs()) {
        throw new IOException("Unable to create data dir " + dataDir);
      }
    }

    File myIdFile = new File(dataDir, "myid");
    PrintWriter w = new PrintWriter(myIdFile);
    w.println(myId);
    w.close();
  }

  /**
   * Make a Properties object holding ZooKeeper config equivalent to zoo.cfg.
   * If there is a zoo.cfg in the classpath, simply read it in. Otherwise parse
   * the corresponding config options from the HBase XML configs and generate
   * the appropriate ZooKeeper properties.
   * @param conf Configuration to read from.
   * @return Properties holding mappings representing ZooKeeper zoo.cfg file.
   */
  public static Properties makeZKProps(Configuration conf) throws SocketException {
    // First check if there is a zoo.cfg in the CLASSPATH. If so, simply read
    // it and grab its configuration properties.
    ClassLoader cl = HQuorumPeer.class.getClassLoader();
    final InputStream inputStream =
      cl.getResourceAsStream(HConstants.ZOOKEEPER_CONFIG_NAME);
    if (inputStream != null) {
      try {
        return parseZooCfg(conf, inputStream);
      } catch (IOException e) {
        LOG.warn("Cannot read " + HConstants.ZOOKEEPER_CONFIG_NAME +
                 ", loading from XML files", e);
      }
    }

    // Otherwise, use the configuration options from HBase's XML files.
    Properties zkProperties = new Properties();

    // Directly map all of the hbase.zookeeper.property.KEY properties.
    for (Entry<String, String> entry : conf) {
      String key = entry.getKey();
      if (key.startsWith(HConstants.ZK_CFG_PROPERTY_PREFIX)) {
        String zkKey = key.substring(ZK_CLIENT_PORT_KEY_PREFIX_LENGTH);
        String value = entry.getValue();
        // If the value has variables substitutions, need to do a get.
        if (value.contains(VARIABLE_START)) {
          value = conf.get(key);
        }
        zkProperties.put(zkKey, value);
      }
    }

    // If clientPort is not set, assign the default.
    if (zkProperties.getProperty(HConstants.CLIENT_PORT_STR) == null) {
      zkProperties.put(HConstants.CLIENT_PORT_STR,
          HConstants.DEFAULT_ZOOKEPER_CLIENT_PORT);
    }

    // Create the server.X properties.
    int peerPort = conf.getInt("hbase.zookeeper.peerport", 2888);
    int leaderPort = conf.getInt("hbase.zookeeper.leaderport", 3888);

    final String[] serverHosts = conf.getStrings(HConstants.ZOOKEEPER_QUORUM,
                                                 "localhost");
    for (int i = 0; i < serverHosts.length; ++i) {
      String serverHost = serverHosts[i];
      String address = serverHost + ":" + peerPort + ":" + leaderPort;
      String key = "server." + i;
      zkProperties.put(key, address);
    }

    LOG.trace("Created ZK properties: " + zkProperties);
    return zkProperties;
  }
  
  /**
   * Return the ZK Quorum servers string given zk properties returned by 
   * makeZKProps
   * @param properties
   * @return
   */
  public static String getZKQuorumServersString(Properties properties) {
    String clientPort = null;
    List<String> servers = new ArrayList<String>();

    // The clientPort option may come after the server.X hosts, so we need to
    // grab everything and then create the final host:port comma separated list.
    boolean anyValid = false;
    for (Entry<Object,Object> property : properties.entrySet()) {
      String key = property.getKey().toString().trim();
      String value = property.getValue().toString().trim();
      if (key.equals("clientPort")) {
        clientPort = value;
      }
      else if (key.startsWith("server.")) {
        /* According to trunk version of QuorumPeerConfig$parseProperties
         * Treat an address contained with [ ] as an IPv6 address if it
         * contains only hex digits and colons. IPv6 addresses will
         * recognized only if specified in this format.
         */
        boolean ipv6 = value.matches("\\[[0-9a-fA-F:]*\\].*");
        String parts[];
        if (ipv6) {
          String blocks[] = value.split("]");
          String ipv6Address = blocks[0].substring(1);
          parts = blocks[1].split(":");
          // The first element in "parts" should be the IP address.
          // enclose with brackets
          parts[0] = "[" + ipv6Address + "]";
        } else {
          parts = value.split(":");
        }

        String host = parts[0];
        servers.add(host);
        try {
          //noinspection ResultOfMethodCallIgnored
          InetAddress.getByName(host);
          anyValid = true;
        } catch (UnknownHostException e) {
          LOG.warn(StringUtils.stringifyException(e));
        }
      }
    }

    if (!anyValid) {
      LOG.error("no valid quorum servers found in " + HConstants.ZOOKEEPER_CONFIG_NAME);
      return null;
    }

    if (clientPort == null) {
      LOG.error("no clientPort found in " + HConstants.ZOOKEEPER_CONFIG_NAME);
      return null;
    }

    if (servers.isEmpty()) {
      LOG.fatal("No server.X lines found in conf/zoo.cfg. HBase must have a " +
                "ZooKeeper cluster configured for its operation.");
      return null;
    }

    StringBuilder hostPortBuilder = new StringBuilder();
    for (int i = 0; i < servers.size(); ++i) {
      String host = servers.get(i);
      if (i > 0) {
        hostPortBuilder.append(',');
      }
      hostPortBuilder.append(host);
      hostPortBuilder.append(':');
      hostPortBuilder.append(clientPort);
    }

    return hostPortBuilder.toString();
  }

  /**
   * Parse ZooKeeper's zoo.cfg, injecting HBase Configuration variables in.
   * This method is used for testing so we can pass our own InputStream.
   * @param conf HBaseConfiguration to use for injecting variables.
   * @param inputStream InputStream to read from.
   * @return Properties parsed from config stream with variables substituted.
   * @throws IOException if anything goes wrong parsing config
   */
  public static Properties parseZooCfg(Configuration conf,
      InputStream inputStream) throws IOException {
    Properties properties = new Properties();
    try {
      properties.load(inputStream);
    } catch (IOException e) {
      final String msg = "fail to read properties from "
        + HConstants.ZOOKEEPER_CONFIG_NAME;
      LOG.fatal(msg);
      throw new IOException(msg, e);
    }
    for (Entry<Object, Object> entry : properties.entrySet()) {
      String value = entry.getValue().toString().trim();
      String key = entry.getKey().toString().trim();
      StringBuilder newValue = new StringBuilder();
      int varStart = value.indexOf(VARIABLE_START);
      int varEnd = 0;
      while (varStart != -1) {
        varEnd = value.indexOf(VARIABLE_END, varStart);
        if (varEnd == -1) {
          String msg = "variable at " + varStart + " has no end marker";
          LOG.fatal(msg);
          throw new IOException(msg);
        }
        String variable = value.substring(varStart + VARIABLE_START_LENGTH, varEnd);

        String substituteValue = System.getProperty(variable);
        if (substituteValue == null) {
          substituteValue = conf.get(variable);
        }
        if (substituteValue == null) {
          String msg = "variable " + variable + " not set in system property "
                     + "or hbase configs";
          LOG.fatal(msg);
          throw new IOException(msg);
        }

        newValue.append(substituteValue);

        varEnd += VARIABLE_END_LENGTH;
        varStart = value.indexOf(VARIABLE_START, varEnd);
      }
      // Special case for 'hbase.cluster.distributed' property being 'true'
      if (key.startsWith("server.")) {
        if (conf.get(HConstants.CLUSTER_DISTRIBUTED).equals(HConstants.CLUSTER_IS_DISTRIBUTED)
            && value.startsWith("localhost")) {
          String msg = "The server in zoo.cfg cannot be set to localhost " +
              "in a fully-distributed setup because it won't be reachable. " +
              "See \"Getting Started\" for more information.";
          LOG.fatal(msg);
          throw new IOException(msg);
        }
      }
      newValue.append(value.substring(varEnd));
      properties.setProperty(key, newValue.toString());
    }
    return properties;
  }
}
