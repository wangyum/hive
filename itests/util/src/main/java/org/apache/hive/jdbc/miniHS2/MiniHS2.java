/*
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

package org.apache.hive.jdbc.miniHS2;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.SettableFuture;

import org.apache.commons.io.FileUtils;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.conf.HiveConf.ConfVars;
import org.apache.hadoop.hive.llap.LlapItUtils;
import org.apache.hadoop.hive.llap.daemon.MiniLlapCluster;
import org.apache.hadoop.hive.metastore.MetaStoreTestUtils;
import org.apache.hadoop.hive.metastore.conf.MetastoreConf;
import org.apache.hadoop.hive.metastore.security.HadoopThriftAuthBridge;
import org.apache.hadoop.hive.ql.exec.Utilities;
import org.apache.hadoop.hive.shims.HadoopShims.MiniDFSShim;
import org.apache.hadoop.hive.shims.HadoopShims.MiniMrShim;
import org.apache.hadoop.hive.shims.ShimLoader;
import org.apache.hive.http.security.PamAuthenticator;
import org.apache.hive.jdbc.Utils;
import org.apache.hive.service.Service;
import org.apache.hive.service.cli.CLIServiceClient;
import org.apache.hive.service.cli.SessionHandle;
import org.apache.hive.service.cli.thrift.ThriftBinaryCLIService;
import org.apache.hive.service.cli.thrift.ThriftCLIServiceClient;
import org.apache.hive.service.cli.thrift.ThriftHttpCLIService;
import org.apache.hive.service.server.HiveServer2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MiniHS2 extends AbstractHiveService {

  private static final Logger LOG = LoggerFactory.getLogger(MiniHS2.class);

  public static final String HS2_BINARY_MODE = "binary";
  public static final String HS2_HTTP_MODE = "http";
  public static final String HS2_ALL_MODE = "all";
  private static final String driverName = "org.apache.hive.jdbc.HiveDriver";
  private static final FsPermission FULL_PERM = new FsPermission((short)00777);
  private static final FsPermission WRITE_ALL_PERM = new FsPermission((short)00733);
  private static final String tmpDir = System.getProperty("test.tmp.dir");
  private static final int DEFAULT_DATANODE_COUNT = 4;
  private HiveServer2 hiveServer2 = null;
  private final File baseDir;
  private final Path baseFsDir;
  private MiniMrShim mr;
  private MiniDFSShim dfs;
  private MiniLlapCluster llapCluster = null;
  private final FileSystem localFS;
  private boolean useMiniKdc = false;
  private final String serverPrincipal;
  private final boolean isMetastoreRemote;
  private final boolean cleanupLocalDirOnStartup;
  private final boolean isMetastoreSecure;
  private MiniClusterType miniClusterType = MiniClusterType.LOCALFS_ONLY;
  private boolean usePortsFromConf = false;
  private PamAuthenticator pamAuthenticator;
  private boolean withHouseKeepingThreads;
  private boolean createTransactionalTables;
  private int hmsPort = 0;

  public void setupQueryHistory() {
    // Query History Service (with a default iceberg table) needs locks and HIVE_LOCKS table to be present,
    // so this is to keep MiniHS2-based unit tests working flawlessly
    getHiveConf().set("iceberg.engine.hive.lock-enabled", "false");

    // for testing purposes, we can persist the query history record almost immediately
    getHiveConf().setIntVar(HiveConf.ConfVars.HIVE_QUERY_HISTORY_BATCH_SIZE, 1);
  }

  public enum MiniClusterType {
    MR,
    TEZ,
    LLAP,
    LOCALFS_ONLY;
  }

  public static class Builder {
    private HiveConf hiveConf = null;
    private MiniClusterType miniClusterType = MiniClusterType.LOCALFS_ONLY;
    private boolean useMiniKdc = false;
    private String serverPrincipal;
    private String serverKeytab;
    private boolean isHTTPTransMode = false;
    private boolean isMetastoreRemote;
    private boolean usePortsFromConf = false;
    private String authType = "KERBEROS";
    private boolean isHA = false;
    private boolean cleanupLocalDirOnStartup = true;
    private boolean withHouseKeepingThreads = false;
    private boolean createTransactionalTables = true;
    private boolean isMetastoreSecure;
    private String metastoreServerPrincipal;
    private String metastoreServerKeyTab;
    private int dataNodes = DEFAULT_DATANODE_COUNT; // default number of datanodes for miniHS2
    private boolean useQueryHistory = false;

    public Builder() {
    }

    public Builder withMiniMR() {
      this.miniClusterType = MiniClusterType.MR;
      return this;
    }
    public Builder withMiniTez() {
      this.miniClusterType = MiniClusterType.TEZ;
      return this;
    }

    public Builder withClusterType(MiniClusterType miniClusterType) {
      this.miniClusterType = miniClusterType;
      return this;
    }

    public Builder withMiniKdc(String serverPrincipal, String serverKeytab) {
      this.useMiniKdc = true;
      this.serverPrincipal = serverPrincipal;
      this.serverKeytab = serverKeytab;
      return this;
    }

    public Builder withAuthenticationType(String authType) {
      this.authType = authType;
      return this;
    }

    public Builder withTransactionalTables(boolean createTransactionalTables) {
      this.createTransactionalTables = createTransactionalTables;
      return this;
    }

    public Builder withRemoteMetastore() {
      return withRemoteMetastore(true);
    }

    public Builder withRemoteMetastore(boolean isMetastoreRemote) {
      this.isMetastoreRemote = isMetastoreRemote;
      return this;
    }

    public Builder withHouseKeepingThreads(boolean withHouseKeepingThreads) {
      this.withHouseKeepingThreads = withHouseKeepingThreads;
      return this;
    }

    public Builder withPortsFromConf(boolean usePortsFromConf) {
      this.usePortsFromConf = usePortsFromConf;
      return this;
    }

    public Builder withSecureRemoteMetastore(String metastoreServerPrincipal, String metastoreServerKeyTab) {
      this.isMetastoreRemote = true;
      this.isMetastoreSecure = true;
      this.metastoreServerPrincipal = metastoreServerPrincipal;
      this.metastoreServerKeyTab = metastoreServerKeyTab;
      return this;
    }

    public Builder withConf(HiveConf hiveConf) {
      this.hiveConf = hiveConf;
      return this;
    }

    public Builder withHA() {
      this.isHA = true;
      return this;
    }

    /**
     * Start HS2 with HTTP transport mode, default is binary mode
     * @return this Builder
     */
    public Builder withHTTPTransport(){
      this.isHTTPTransMode = true;
      return this;
    }

    public Builder cleanupLocalDirOnStartup(boolean val) {
      this.cleanupLocalDirOnStartup = val;
      return this;
    }

    /**
     * Set the number of datanodes to be used by HS2.
     * @param count the number of datanodes
     * @return this Builder
     */
    public Builder withDataNodes(int count) {
      this.dataNodes = count;
      return this;
    }

    public Builder withQueryHistory(boolean useQueryHistory){
      this.useQueryHistory = useQueryHistory;
      return this;
    }

    public MiniHS2 build() throws Exception {
      if (miniClusterType == MiniClusterType.MR && useMiniKdc) {
        throw new IOException("Can't create secure miniMr ... yet");
      }
      if (hiveConf == null) {
        this.hiveConf = new HiveConf();
      }
      Iterator<Map.Entry<String, String>> iter = hiveConf.iterator();
      while (iter.hasNext()) {
        String key = iter.next().getKey();
        hiveConf.set(key, hiveConf.get(key));
      }
      if (isHTTPTransMode) {
        hiveConf.setVar(ConfVars.HIVE_SERVER2_TRANSPORT_MODE, HS2_HTTP_MODE);
      } else {
        hiveConf.setVar(ConfVars.HIVE_SERVER2_TRANSPORT_MODE, HS2_BINARY_MODE);
      }

      hiveConf.setBoolVar(ConfVars.HIVE_QUERY_HISTORY_ENABLED, useQueryHistory);

      return new MiniHS2(hiveConf, miniClusterType, useMiniKdc, serverPrincipal, serverKeytab,
          isMetastoreRemote, withHouseKeepingThreads, createTransactionalTables, usePortsFromConf, authType, isHA,
          cleanupLocalDirOnStartup, isMetastoreSecure, metastoreServerPrincipal, metastoreServerKeyTab, dataNodes);
    }
  }

  public MiniMrShim getMr() {
    return mr;
  }

  public void setMr(MiniMrShim mr) {
    this.mr = mr;
  }

  public MiniDFSShim getDfs() {
    return dfs;
  }

  public void setDfs(MiniDFSShim dfs) {
    this.dfs = dfs;
  }

  public FileSystem getLocalFS() {
    return localFS;
  }

  public MiniClusterType getMiniClusterType() {
    return miniClusterType;
  }

  public void setMiniClusterType(MiniClusterType miniClusterType) {
    this.miniClusterType = miniClusterType;
  }

  public boolean isUseMiniKdc() {
    return useMiniKdc;
  }

  private MiniHS2(HiveConf hiveConf, MiniClusterType miniClusterType, boolean useMiniKdc,
      String serverPrincipal, String serverKeytab, boolean isMetastoreRemote,
      boolean withHouseKeepingThreads, boolean createTransactionalTables,
      boolean usePortsFromConf, String authType, boolean isHA, boolean cleanupLocalDirOnStartup,
      boolean isMetastoreSecure, String metastoreServerPrincipal, String metastoreKeyTab,
      int dataNodes) throws Exception {
    // Always use localhost for hostname as some tests like SSL CN validation ones
    // are tied to localhost being present in the certificate name
    super(
        hiveConf,
        "localhost",
        (usePortsFromConf ? hiveConf.getIntVar(HiveConf.ConfVars.HIVE_SERVER2_THRIFT_PORT) : MetaStoreTestUtils
            .findFreePort()),
        (usePortsFromConf ? hiveConf.getIntVar(HiveConf.ConfVars.HIVE_SERVER2_THRIFT_HTTP_PORT) : MetaStoreTestUtils
            .findFreePort()),
        (usePortsFromConf ? hiveConf.getIntVar(ConfVars.HIVE_SERVER2_WEBUI_PORT) : MetaStoreTestUtils
            .findFreePort()),
        (usePortsFromConf ? hiveConf.getIntVar(ConfVars.HIVE_SERVER2_ACTIVE_PASSIVE_HA_HEALTHCHECK_PORT) : MetaStoreTestUtils
            .findFreePort()));
    hiveConf.setLongVar(ConfVars.HIVE_SERVER2_MAX_START_ATTEMPTS, 3l);
    hiveConf.setTimeVar(ConfVars.HIVE_SERVER2_SLEEP_INTERVAL_BETWEEN_START_ATTEMPTS, 10,
        TimeUnit.SECONDS);
    hiveConf.setBoolVar(ConfVars.HIVE_SCHEDULED_QUERIES_EXECUTOR_ENABLED, false);
    this.miniClusterType = miniClusterType;
    this.useMiniKdc = useMiniKdc;
    this.serverPrincipal = serverPrincipal;
    this.isMetastoreRemote = isMetastoreRemote;
    this.isMetastoreSecure = isMetastoreSecure;
    this.cleanupLocalDirOnStartup = cleanupLocalDirOnStartup;
    this.usePortsFromConf = usePortsFromConf;
    this.withHouseKeepingThreads = withHouseKeepingThreads;
    this.createTransactionalTables = createTransactionalTables;
    baseDir = getBaseDir();
    localFS = FileSystem.getLocal(hiveConf);
    FileSystem fs;

    if (miniClusterType != MiniClusterType.LOCALFS_ONLY) {
      // Initialize dfs
      dfs = ShimLoader.getHadoopShims().getMiniDfs(hiveConf, dataNodes, true, null, isHA);
      fs = dfs.getFileSystem();
      String uriString = fs.getUri().toString();

      // Initialize the execution engine based on cluster type
      switch (miniClusterType) {
      case TEZ:
        // Change the engine to tez
        hiveConf.setVar(ConfVars.HIVE_EXECUTION_ENGINE, "tez");
        // TODO: This should be making use of confDir to load configs setup for Tez, etc.
        mr = ShimLoader.getHadoopShims().getMiniTezCluster(hiveConf, 2, uriString, false);
        break;
      case LLAP:
        if (usePortsFromConf) {
          hiveConf.setBoolean("minillap.usePortsFromConf", true);
        }
        llapCluster = LlapItUtils.startAndGetMiniLlapCluster(hiveConf, null, null);

        mr = ShimLoader.getHadoopShims().getMiniTezCluster(hiveConf, 2, uriString, true);
        break;
      case MR:
        mr = ShimLoader.getHadoopShims().getMiniMrCluster(hiveConf, 2, uriString, 1);
        break;
      default:
        throw new IllegalArgumentException("Unsupported cluster type " + mr);
      }
      // store the config in system properties
      mr.setupConfiguration(getHiveConf());
      baseFsDir = new Path(new Path(fs.getUri()), HiveConf.ConfVars.SCRATCH_DIR.getDefaultValue());
    } else {
      // This is FS only mode, just initialize the dfs root directory.
      fs = FileSystem.getLocal(hiveConf);
      baseFsDir = new Path("file://" + baseDir.toURI().getPath());

      if (cleanupLocalDirOnStartup) {
        // Cleanup baseFsDir since it can be shared across tests.
        LOG.info("Attempting to cleanup baseFsDir: {} while setting up MiniHS2", baseDir);
        Preconditions.checkState(baseFsDir.depth() >= 3); // Avoid "/tmp", directories closer to "/"
        fs.delete(baseFsDir, true);
      }
    }
    if (useMiniKdc) {
      hiveConf.setVar(ConfVars.HIVE_SERVER2_KERBEROS_PRINCIPAL, serverPrincipal);
      hiveConf.setVar(ConfVars.HIVE_SERVER2_KERBEROS_KEYTAB, serverKeytab);
      hiveConf.setVar(ConfVars.HIVE_SERVER2_AUTHENTICATION, authType);
    }

    if (isMetastoreSecure) {
      hiveConf.setVar(ConfVars.METASTORE_KERBEROS_PRINCIPAL, metastoreServerPrincipal);
      hiveConf.setVar(ConfVars.METASTORE_KERBEROS_KEYTAB_FILE, metastoreKeyTab);
      hiveConf.setBoolVar(ConfVars.METASTORE_USE_THRIFT_SASL, true);
    }

    fs.mkdirs(baseFsDir);
    Path wareHouseDir = new Path(baseFsDir, "warehouse");
    // Create warehouse with 777, so that user impersonation has no issues.
    FileSystem.mkdirs(fs, wareHouseDir, FULL_PERM);

    fs.mkdirs(wareHouseDir);
    setWareHouseDir(wareHouseDir.toString());
    if (!usePortsFromConf) {
      // reassign a new port, just in case if one of the MR services grabbed the last one
      setBinaryPort(MetaStoreTestUtils.findFreePort());
    }
    hiveConf.setVar(ConfVars.HIVE_SERVER2_THRIFT_BIND_HOST, getHost());
    hiveConf.setIntVar(ConfVars.HIVE_SERVER2_THRIFT_PORT, getBinaryPort());
    hiveConf.setIntVar(ConfVars.HIVE_SERVER2_THRIFT_HTTP_PORT, getHttpPort());
    hiveConf.setIntVar(ConfVars.HIVE_SERVER2_WEBUI_PORT, getWebPort());
    hiveConf.setIntVar(ConfVars.HIVE_SERVER2_ACTIVE_PASSIVE_HA_HEALTHCHECK_PORT, getHealthCheckHAPort());

    Path scratchDir = new Path(baseFsDir, "scratch");
    // Create root scratchdir with write all, so that user impersonation has no issues.
    Utilities.createDirsWithPermission(hiveConf, scratchDir, WRITE_ALL_PERM, true);
    System.setProperty(HiveConf.ConfVars.SCRATCH_DIR.varname, scratchDir.toString());
    hiveConf.setVar(ConfVars.SCRATCH_DIR, scratchDir.toString());

    String localScratchDir = baseDir.getPath() + File.separator + "scratch";
    System.setProperty(HiveConf.ConfVars.LOCAL_SCRATCH_DIR.varname, localScratchDir);
    hiveConf.setVar(ConfVars.LOCAL_SCRATCH_DIR, localScratchDir);
  }

  public MiniHS2(HiveConf hiveConf) throws Exception {
    this(hiveConf, MiniClusterType.LOCALFS_ONLY);
  }

  public MiniHS2(HiveConf hiveConf, MiniClusterType clusterType) throws Exception {
    this(hiveConf, clusterType, false, false);
  }

  public MiniHS2(HiveConf hiveConf, MiniClusterType clusterType, boolean usePortsFromConf, boolean isMetastoreRemote)
      throws Exception {
    this(hiveConf, clusterType, false, null, null,
        isMetastoreRemote, false, true, usePortsFromConf, "KERBEROS", false, true,
        false, null, null, DEFAULT_DATANODE_COUNT);
  }

  public void start(Map<String, String> confOverlay) throws Exception {
    if (isMetastoreRemote) {
      hmsPort = MetaStoreTestUtils.startMetaStoreWithRetry(HadoopThriftAuthBridge.getBridge(), getHiveConf(),
              true, false, withHouseKeepingThreads, false, createTransactionalTables);
      setWareHouseDir(MetastoreConf.getVar(getHiveConf(), MetastoreConf.ConfVars.WAREHOUSE));
    }

    // Set confOverlay parameters
    for (Map.Entry<String, String> entry : confOverlay.entrySet()) {
      setConfProperty(entry.getKey(), entry.getValue());
    }

    // setup Query History service here, this will take care of unit tests that use the MiniHS2.Builder or the
    // constructor directly
    setupQueryHistory();

    Exception hs2Exception = null;
    boolean hs2Started = false;
    for (int tryCount = 0; (tryCount < MetaStoreTestUtils.RETRY_COUNT); tryCount++) {
      try {
        hiveServer2 = new HiveServer2();
        if (pamAuthenticator != null) {
          hiveServer2.setPamAuthenticator(pamAuthenticator);
        }
        hiveServer2.init(getHiveConf());
        hiveServer2.start();
        hs2Started = true;
        break;
      } catch (Exception t) {
        hs2Exception = t;
        if (usePortsFromConf) {
          hs2Started = false;
          break;
        } else {
          HiveConf.setIntVar(getHiveConf(), HiveConf.ConfVars.HIVE_SERVER2_THRIFT_PORT,
              MetaStoreTestUtils.findFreePort());
          HiveConf.setIntVar(getHiveConf(), HiveConf.ConfVars.HIVE_SERVER2_THRIFT_HTTP_PORT,
              MetaStoreTestUtils.findFreePort());
          HiveConf.setIntVar(getHiveConf(), HiveConf.ConfVars.HIVE_SERVER2_WEBUI_PORT,
              MetaStoreTestUtils.findFreePort());
          resetSamlACSUrl();
        }
      }
    }

    if (!hs2Started) {
      throw(hs2Exception);
    }

    waitForStartup();
    setStarted(true);
  }

  private void resetSamlACSUrl() throws URISyntaxException {
    if (isSAMLAuth()) {
      // in case this is a SAML Auth miniHS2 we should make sure that the
      // assertion consumer service url is appropriately reconfigured if the http
      // port changed.
      String existingAcs = HiveConf
          .getVar(getHiveConf(), ConfVars.HIVE_SERVER2_SAML_CALLBACK_URL);
      String existingPort = String.valueOf(new URI(existingAcs).getPort());
      String newAcs = existingAcs.replace(":" + existingPort, ":" + HiveConf
          .getVar(getHiveConf(), ConfVars.HIVE_SERVER2_THRIFT_HTTP_PORT));
      HiveConf.setVar(getHiveConf(), ConfVars.HIVE_SERVER2_SAML_CALLBACK_URL, newAcs);
    }
  }

  private boolean isSAMLAuth() {
    return "SAML"
        .equals(HiveConf.getVar(getHiveConf(), ConfVars.HIVE_SERVER2_SAML_CALLBACK_URL));
  }

  public void graceful_stop() {
    verifyStarted();
    hiveServer2.graceful_stop();
  }

  public void stop() {
    verifyStarted();
    // Currently there is no way to stop the MetaStore service. It will be stopped when the
    // test JVM exits. This is how other tests are also using MetaStore server.

    hiveServer2.stop();
    setStarted(false);
    try {
      if (llapCluster != null) {
        llapCluster.stop();
      }
      if (mr != null) {
        mr.shutdown();
        mr = null;
      }
      if (dfs != null) {
        dfs.shutdown();
        dfs = null;
      }
    } catch (IOException e) {
      // Ignore errors cleaning up miniMR
    }
  }

  public void cleanup() {
    FileUtils.deleteQuietly(baseDir);
  }


  public boolean isLeader() {
    return hiveServer2.isLeader();
  }

  public SettableFuture<Boolean> getIsLeaderTestFuture() {
    return hiveServer2.getIsLeaderTestFuture();
  }

  public SettableFuture<Boolean> getNotLeaderTestFuture() {
    return hiveServer2.getNotLeaderTestFuture();
  }

  public void setPamAuthenticator(final PamAuthenticator pamAuthenticator) {
    this.pamAuthenticator = pamAuthenticator;
  }

  public int getOpenSessionsCount() {
    return hiveServer2.getOpenSessionsCount();
  }

  public CLIServiceClient getServiceClient() {
    verifyStarted();
    return getServiceClientInternal();
  }

  public HiveConf getServerConf() {
    if (hiveServer2 != null) {
      return hiveServer2.getHiveConf();
    }
    return null;
  }

  public CLIServiceClient getServiceClientInternal() {
    for (Service service : hiveServer2.getServices()) {
      if (service instanceof ThriftBinaryCLIService) {
        return new ThriftCLIServiceClient((ThriftBinaryCLIService) service);
      }
      if (service instanceof ThriftHttpCLIService) {
        return new ThriftCLIServiceClient((ThriftHttpCLIService) service);
      }
    }
    throw new IllegalStateException("HiveServer2 not running Thrift service");
  }

  /**
   * return connection URL for this server instance
   * @return
   * @throws Exception
   */
  public String getJdbcURL() throws Exception {
    return getJdbcURL("default");
  }

  /**
   * return connection URL for this server instance
   * @param dbName - DB name to be included in the URL
   * @return
   * @throws Exception
   */
  public String getJdbcURL(String dbName) throws Exception {
    return getJdbcURL(dbName, "");
  }

  /**
   * return connection URL for this server instance
   * @param dbName - DB name to be included in the URL
   * @param sessionConfExt - Additional string to be appended to sessionConf part of url
   * @return
   * @throws Exception
   */
  public String getJdbcURL(String dbName, String sessionConfExt) throws Exception {
    return getJdbcURL(dbName, sessionConfExt, "");
  }

  /**
   * return connection URL for this server instance
   * @param dbName - DB name to be included in the URL
   * @param sessionConfExt - Additional string to be appended to sessionConf part of url
   * @param hiveConfExt - Additional string to be appended to HiveConf part of url (excluding the ?)
   * @return
   * @throws Exception
   */
  public String getJdbcURL(String dbName, String sessionConfExt, String hiveConfExt)
      throws Exception {
    sessionConfExt = (sessionConfExt == null ? "" : sessionConfExt);
    hiveConfExt = (hiveConfExt == null ? "" : hiveConfExt);
    // Strip the leading ";" if provided
    // (this is the assumption with which we're going to start configuring sessionConfExt)
    if (sessionConfExt.startsWith(";")) {
      sessionConfExt = sessionConfExt.substring(1);
    }
    if (isUseMiniKdc()) {
      sessionConfExt = "principal=" + serverPrincipal + ";" + sessionConfExt;
    }
    if (isHttpTransportMode()) {
      sessionConfExt = "transportMode=http;httpPath=cliservice" + ";" + sessionConfExt;
    }
    String baseJdbcURL;
    if (isDynamicServiceDiscovery()) {
      String namespace = getServerConf().getVar(HiveConf.ConfVars.HIVE_SERVER2_ZOOKEEPER_NAMESPACE);
      String serviceDiscoveryMode = Utils.JdbcConnectionParams.SERVICE_DISCOVERY_MODE_ZOOKEEPER;
      if (HiveConf.getBoolVar(getServerConf(), ConfVars.HIVE_SERVER2_ACTIVE_PASSIVE_HA_ENABLE)) {
        namespace = getServerConf().getVar(ConfVars.HIVE_SERVER2_ACTIVE_PASSIVE_HA_REGISTRY_NAMESPACE);
        serviceDiscoveryMode = Utils.JdbcConnectionParams.SERVICE_DISCOVERY_MODE_ZOOKEEPER_HA;
      }
      sessionConfExt = "serviceDiscoveryMode=" + serviceDiscoveryMode + ";zooKeeperNamespace="
        + namespace + ";" + sessionConfExt;
      baseJdbcURL = getZKBaseJdbcURL();
    } else {
      baseJdbcURL = getBaseJdbcURL();
    }

    baseJdbcURL = baseJdbcURL + dbName;
    if (!sessionConfExt.isEmpty()) {
      baseJdbcURL = baseJdbcURL + ";" + sessionConfExt;
    }
    if ((hiveConfExt != null) && (!hiveConfExt.trim().isEmpty())) {
      baseJdbcURL = baseJdbcURL + "?" + hiveConfExt;
    }
    return baseJdbcURL;
  }

  /**
   * Build base JDBC URL
   * @return
   */
  public String getBaseJdbcURL() {
    if(isHttpTransportMode()) {
      return "jdbc:hive2://" + getHost() + ":" + getHttpPort() + "/";
    }
    else {
      return "jdbc:hive2://" + getHost() + ":" + getBinaryPort() + "/";
    }
  }

  /**
   * Build base JDBC URL
   * @return URL
   */
  public String getBaseHttpJdbcURL() {
    String transportMode = getConfProperty(ConfVars.HIVE_SERVER2_TRANSPORT_MODE.varname);
    if(!transportMode.equalsIgnoreCase(HS2_ALL_MODE)) {
      return getBaseJdbcURL();
    }
    return "jdbc:hive2://" + getHost() + ":" + getHttpPort() + "/";
  }

  /**
   * Build zk base JDBC URL.
   * @return URL
   */
  private String getZKBaseJdbcURL() throws Exception {
    HiveConf hiveConf = getServerConf();
    if (hiveConf != null) {
      String zkEnsemble =  hiveConf.getZKConfig().getQuorumServers();
      return "jdbc:hive2://" + zkEnsemble + "/";
    }
    throw new Exception("Server's HiveConf is null. Unable to read ZooKeeper configs.");
  }

  /**
   * Returns HTTP connection URL for this server instance.
   * @return URL
   * @throws Exception
   */
  public synchronized String getHttpJdbcURL() throws Exception {
    String transportMode = getConfProperty(ConfVars.HIVE_SERVER2_TRANSPORT_MODE.varname);
    if(!transportMode.equalsIgnoreCase(HS2_ALL_MODE)) {
      return getJdbcURL();
    }
    try {
      getHiveConf().setVar(ConfVars.HIVE_SERVER2_TRANSPORT_MODE, HS2_HTTP_MODE);
      return getJdbcURL("default");
    } finally {
      getHiveConf().setVar(ConfVars.HIVE_SERVER2_TRANSPORT_MODE, HS2_ALL_MODE);
    }
  }

  private boolean isHttpTransportMode() {
    String transportMode = getConfProperty(ConfVars.HIVE_SERVER2_TRANSPORT_MODE.varname);
    return transportMode != null && (transportMode.equalsIgnoreCase(HS2_HTTP_MODE));
  }

  private boolean isDynamicServiceDiscovery() throws Exception {
    HiveConf hiveConf = getServerConf();
    if (hiveConf == null) {
      throw new Exception("Server's HiveConf is null. Unable to read ZooKeeper configs.");
    }
    if (hiveConf.getBoolVar(ConfVars.HIVE_SERVER2_SUPPORT_DYNAMIC_SERVICE_DISCOVERY)) {
      return true;
    }
    return false;
  }

  public static String getJdbcDriverName() {
    return driverName;
  }

  public MiniMrShim getMR() {
    return mr;
  }

  public MiniDFSShim getDFS() {
    return dfs;
  }

  private void waitForStartup() throws Exception {
    int waitTime = 0;
    long startupTimeout = 1000L * 1000L;
    CLIServiceClient hs2Client = getServiceClientInternal();
    SessionHandle sessionHandle = null;
    do {
      Thread.sleep(500L);
      waitTime += 500L;
      if (waitTime > startupTimeout) {
        throw new TimeoutException("Couldn't access new HiveServer2: " + getJdbcURL());
      }
      try {
        Map <String, String> sessionConf = new HashMap<String, String>();
        /**
        if (isUseMiniKdc()) {
          getMiniKdc().loginUser(getMiniKdc().getDefaultUserPrincipal());
          sessionConf.put("principal", serverPrincipal);
        }
         */
        sessionHandle = hs2Client.openSession("foo", "bar", sessionConf);
      } catch (Exception e) {
        if (e.getMessage().contains("Cannot open sessions on an inactive HS2")) {
          // Passive HS2 has started. TODO: seems fragile
          return;
        }
        // service not started yet
        continue;
      }
      hs2Client.closeSession(sessionHandle);
      break;
    } while (true);
  }

  public Service.STATE getState() {
    return hiveServer2.getServiceState();
  }

  static File getBaseDir() {
    File baseDir = new File(tmpDir + "/local_base");
    return baseDir;
  }

  public static void cleanupLocalDir() throws IOException {
    File baseDir = getBaseDir();
    try {
      org.apache.hadoop.hive.common.FileUtils.deleteDirectory(baseDir);
    } catch (FileNotFoundException e) {
      // Ignore. Safe if it does not exist.
    }
  }

  public int getHmsPort() {
    return hmsPort;
  }
}
