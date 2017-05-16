/**
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
package org.apache.hadoop.hbase.regionserver;

import com.google.common.annotations.VisibleForTesting;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import org.apache.commons.lang.mutable.MutableObject;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.ByteBufferCell;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellScannable;
import org.apache.hadoop.hbase.CellScanner;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.DoNotRetryIOException;
import org.apache.hadoop.hbase.DroppedSnapshotException;
import org.apache.hadoop.hbase.HBaseIOException;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.MultiActionResultTooLarge;
import org.apache.hadoop.hbase.NotServingRegionException;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.UnknownScannerException;
import org.apache.hadoop.hbase.classification.InterfaceAudience;
import org.apache.hadoop.hbase.client.Append;
import org.apache.hadoop.hbase.client.ConnectionUtils;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Durability;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Increment;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.RegionReplicaUtil;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.RowMutations;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.VersionInfoUtil;
import org.apache.hadoop.hbase.conf.ConfigurationObserver;
import org.apache.hadoop.hbase.exceptions.FailedSanityCheckException;
import org.apache.hadoop.hbase.exceptions.OutOfOrderScannerNextException;
import org.apache.hadoop.hbase.exceptions.ScannerResetException;
import org.apache.hadoop.hbase.filter.ByteArrayComparable;
import org.apache.hadoop.hbase.filter.CompareFilter.CompareOp;
import org.apache.hadoop.hbase.ipc.HBaseRPCErrorHandler;
import org.apache.hadoop.hbase.ipc.HBaseRpcController;
import org.apache.hadoop.hbase.ipc.PriorityFunction;
import org.apache.hadoop.hbase.ipc.QosPriority;
import org.apache.hadoop.hbase.ipc.RpcCallContext;
import org.apache.hadoop.hbase.ipc.RpcCallback;
import org.apache.hadoop.hbase.ipc.RpcServer;
import org.apache.hadoop.hbase.ipc.RpcServer.BlockingServiceAndInterface;
import org.apache.hadoop.hbase.ipc.RpcServerFactory;
import org.apache.hadoop.hbase.ipc.RpcServerInterface;
import org.apache.hadoop.hbase.ipc.ServerNotRunningYetException;
import org.apache.hadoop.hbase.ipc.ServerRpcController;
import org.apache.hadoop.hbase.master.MasterRpcServices;
import org.apache.hadoop.hbase.quotas.OperationQuota;
import org.apache.hadoop.hbase.quotas.RegionServerQuotaManager;
import org.apache.hadoop.hbase.regionserver.HRegion.RegionScannerImpl;
import org.apache.hadoop.hbase.regionserver.Leases.Lease;
import org.apache.hadoop.hbase.regionserver.Leases.LeaseStillHeldException;
import org.apache.hadoop.hbase.regionserver.Region.Operation;
import org.apache.hadoop.hbase.regionserver.ScannerContext.LimitScope;
import org.apache.hadoop.hbase.regionserver.handler.OpenMetaHandler;
import org.apache.hadoop.hbase.regionserver.handler.OpenPriorityRegionHandler;
import org.apache.hadoop.hbase.regionserver.handler.OpenRegionHandler;
import org.apache.hadoop.hbase.regionserver.wal.WALEdit;
import org.apache.hadoop.hbase.security.User;
import org.apache.hadoop.hbase.shaded.com.google.protobuf.ByteString;
import org.apache.hadoop.hbase.shaded.com.google.protobuf.Message;
import org.apache.hadoop.hbase.shaded.com.google.protobuf.RpcController;
import org.apache.hadoop.hbase.shaded.com.google.protobuf.ServiceException;
import org.apache.hadoop.hbase.shaded.com.google.protobuf.TextFormat;
import org.apache.hadoop.hbase.shaded.com.google.protobuf.UnsafeByteOperations;
import org.apache.hadoop.hbase.shaded.protobuf.ProtobufUtil;
import org.apache.hadoop.hbase.shaded.protobuf.RequestConverter;
import org.apache.hadoop.hbase.shaded.protobuf.ResponseConverter;
import org.apache.hadoop.hbase.shaded.protobuf.generated.AdminProtos.AdminService;
import org.apache.hadoop.hbase.shaded.protobuf.generated.AdminProtos.ClearCompactionQueuesRequest;
import org.apache.hadoop.hbase.shaded.protobuf.generated.AdminProtos.ClearCompactionQueuesResponse;
import org.apache.hadoop.hbase.shaded.protobuf.generated.AdminProtos.CloseRegionForSplitOrMergeRequest;
import org.apache.hadoop.hbase.shaded.protobuf.generated.AdminProtos.CloseRegionForSplitOrMergeResponse;
import org.apache.hadoop.hbase.shaded.protobuf.generated.AdminProtos.CloseRegionRequest;
import org.apache.hadoop.hbase.shaded.protobuf.generated.AdminProtos.CloseRegionResponse;
import org.apache.hadoop.hbase.shaded.protobuf.generated.AdminProtos.CompactRegionRequest;
import org.apache.hadoop.hbase.shaded.protobuf.generated.AdminProtos.CompactRegionResponse;
import org.apache.hadoop.hbase.shaded.protobuf.generated.AdminProtos.FlushRegionRequest;
import org.apache.hadoop.hbase.shaded.protobuf.generated.AdminProtos.FlushRegionResponse;
import org.apache.hadoop.hbase.shaded.protobuf.generated.AdminProtos.GetOnlineRegionRequest;
import org.apache.hadoop.hbase.shaded.protobuf.generated.AdminProtos.GetOnlineRegionResponse;
import org.apache.hadoop.hbase.shaded.protobuf.generated.AdminProtos.GetRegionInfoRequest;
import org.apache.hadoop.hbase.shaded.protobuf.generated.AdminProtos.GetRegionInfoResponse;
import org.apache.hadoop.hbase.shaded.protobuf.generated.AdminProtos.GetRegionLoadRequest;
import org.apache.hadoop.hbase.shaded.protobuf.generated.AdminProtos.GetRegionLoadResponse;
import org.apache.hadoop.hbase.shaded.protobuf.generated.AdminProtos.GetServerInfoRequest;
import org.apache.hadoop.hbase.shaded.protobuf.generated.AdminProtos.GetServerInfoResponse;
import org.apache.hadoop.hbase.shaded.protobuf.generated.AdminProtos.GetStoreFileRequest;
import org.apache.hadoop.hbase.shaded.protobuf.generated.AdminProtos.GetStoreFileResponse;
import org.apache.hadoop.hbase.shaded.protobuf.generated.AdminProtos.OpenRegionRequest;
import org.apache.hadoop.hbase.shaded.protobuf.generated.AdminProtos.OpenRegionRequest.RegionOpenInfo;
import org.apache.hadoop.hbase.shaded.protobuf.generated.AdminProtos.OpenRegionResponse;
import org.apache.hadoop.hbase.shaded.protobuf.generated.AdminProtos.OpenRegionResponse.RegionOpeningState;
import org.apache.hadoop.hbase.shaded.protobuf.generated.AdminProtos.ReplicateWALEntryRequest;
import org.apache.hadoop.hbase.shaded.protobuf.generated.AdminProtos.ReplicateWALEntryResponse;
import org.apache.hadoop.hbase.shaded.protobuf.generated.AdminProtos.RollWALWriterRequest;
import org.apache.hadoop.hbase.shaded.protobuf.generated.AdminProtos.RollWALWriterResponse;
import org.apache.hadoop.hbase.shaded.protobuf.generated.AdminProtos.SplitRegionRequest;
import org.apache.hadoop.hbase.shaded.protobuf.generated.AdminProtos.SplitRegionResponse;
import org.apache.hadoop.hbase.shaded.protobuf.generated.AdminProtos.StopServerRequest;
import org.apache.hadoop.hbase.shaded.protobuf.generated.AdminProtos.StopServerResponse;
import org.apache.hadoop.hbase.shaded.protobuf.generated.AdminProtos.UpdateConfigurationRequest;
import org.apache.hadoop.hbase.shaded.protobuf.generated.AdminProtos.UpdateConfigurationResponse;
import org.apache.hadoop.hbase.shaded.protobuf.generated.AdminProtos.UpdateFavoredNodesRequest;
import org.apache.hadoop.hbase.shaded.protobuf.generated.AdminProtos.UpdateFavoredNodesResponse;
import org.apache.hadoop.hbase.shaded.protobuf.generated.AdminProtos.WALEntry;
import org.apache.hadoop.hbase.shaded.protobuf.generated.AdminProtos.WarmupRegionRequest;
import org.apache.hadoop.hbase.shaded.protobuf.generated.AdminProtos.WarmupRegionResponse;
import org.apache.hadoop.hbase.shaded.protobuf.generated.ClientProtos;
import org.apache.hadoop.hbase.shaded.protobuf.generated.ClientProtos.Action;
import org.apache.hadoop.hbase.shaded.protobuf.generated.ClientProtos.BulkLoadHFileRequest;
import org.apache.hadoop.hbase.shaded.protobuf.generated.ClientProtos.BulkLoadHFileRequest.FamilyPath;
import org.apache.hadoop.hbase.shaded.protobuf.generated.ClientProtos.BulkLoadHFileResponse;
import org.apache.hadoop.hbase.shaded.protobuf.generated.ClientProtos.CleanupBulkLoadRequest;
import org.apache.hadoop.hbase.shaded.protobuf.generated.ClientProtos.CleanupBulkLoadResponse;
import org.apache.hadoop.hbase.shaded.protobuf.generated.ClientProtos.ClientService;
import org.apache.hadoop.hbase.shaded.protobuf.generated.ClientProtos.Condition;
import org.apache.hadoop.hbase.shaded.protobuf.generated.ClientProtos.CoprocessorServiceRequest;
import org.apache.hadoop.hbase.shaded.protobuf.generated.ClientProtos.CoprocessorServiceResponse;
import org.apache.hadoop.hbase.shaded.protobuf.generated.ClientProtos.GetRequest;
import org.apache.hadoop.hbase.shaded.protobuf.generated.ClientProtos.GetResponse;
import org.apache.hadoop.hbase.shaded.protobuf.generated.ClientProtos.MultiRegionLoadStats;
import org.apache.hadoop.hbase.shaded.protobuf.generated.ClientProtos.MultiRequest;
import org.apache.hadoop.hbase.shaded.protobuf.generated.ClientProtos.MultiResponse;
import org.apache.hadoop.hbase.shaded.protobuf.generated.ClientProtos.MutateRequest;
import org.apache.hadoop.hbase.shaded.protobuf.generated.ClientProtos.MutateResponse;
import org.apache.hadoop.hbase.shaded.protobuf.generated.ClientProtos.MutationProto;
import org.apache.hadoop.hbase.shaded.protobuf.generated.ClientProtos.MutationProto.MutationType;
import org.apache.hadoop.hbase.shaded.protobuf.generated.ClientProtos.PrepareBulkLoadRequest;
import org.apache.hadoop.hbase.shaded.protobuf.generated.ClientProtos.PrepareBulkLoadResponse;
import org.apache.hadoop.hbase.shaded.protobuf.generated.ClientProtos.RegionAction;
import org.apache.hadoop.hbase.shaded.protobuf.generated.ClientProtos.RegionActionResult;
import org.apache.hadoop.hbase.shaded.protobuf.generated.ClientProtos.ResultOrException;
import org.apache.hadoop.hbase.shaded.protobuf.generated.ClientProtos.ScanRequest;
import org.apache.hadoop.hbase.shaded.protobuf.generated.ClientProtos.ScanResponse;
import org.apache.hadoop.hbase.shaded.protobuf.generated.ClusterStatusProtos;
import org.apache.hadoop.hbase.shaded.protobuf.generated.ClusterStatusProtos.RegionLoad;
import org.apache.hadoop.hbase.shaded.protobuf.generated.HBaseProtos.NameBytesPair;
import org.apache.hadoop.hbase.shaded.protobuf.generated.HBaseProtos.NameInt64Pair;
import org.apache.hadoop.hbase.shaded.protobuf.generated.HBaseProtos.RegionInfo;
import org.apache.hadoop.hbase.shaded.protobuf.generated.HBaseProtos.RegionSpecifier;
import org.apache.hadoop.hbase.shaded.protobuf.generated.HBaseProtos.RegionSpecifier.RegionSpecifierType;
import org.apache.hadoop.hbase.shaded.protobuf.generated.MapReduceProtos.ScanMetrics;
import org.apache.hadoop.hbase.shaded.protobuf.generated.RPCProtos.RequestHeader;
import org.apache.hadoop.hbase.shaded.protobuf.generated.WALProtos.BulkLoadDescriptor;
import org.apache.hadoop.hbase.shaded.protobuf.generated.WALProtos.CompactionDescriptor;
import org.apache.hadoop.hbase.shaded.protobuf.generated.WALProtos.FlushDescriptor;
import org.apache.hadoop.hbase.shaded.protobuf.generated.WALProtos.RegionEventDescriptor;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.DNS;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.hadoop.hbase.util.ServerRegionReplicaUtil;
import org.apache.hadoop.hbase.util.Strings;
import org.apache.hadoop.hbase.wal.WAL;
import org.apache.hadoop.hbase.wal.WALKey;
import org.apache.hadoop.hbase.wal.WALSplitter;
import org.apache.hadoop.hbase.zookeeper.ZKSplitLog;
import org.apache.zookeeper.KeeperException;

/**
 * Implements the regionserver RPC services.
 */
@InterfaceAudience.Private
@SuppressWarnings("deprecation")
public class RSRpcServices implements HBaseRPCErrorHandler,
    AdminService.BlockingInterface, ClientService.BlockingInterface, PriorityFunction,
    ConfigurationObserver {
  protected static final Log LOG = LogFactory.getLog(RSRpcServices.class);

  /** RPC scheduler to use for the region server. */
  public static final String REGION_SERVER_RPC_SCHEDULER_FACTORY_CLASS =
    "hbase.region.server.rpc.scheduler.factory.class";

  /**
   * Minimum allowable time limit delta (in milliseconds) that can be enforced during scans. This
   * configuration exists to prevent the scenario where a time limit is specified to be so
   * restrictive that the time limit is reached immediately (before any cells are scanned).
   */
  private static final String REGION_SERVER_RPC_MINIMUM_SCAN_TIME_LIMIT_DELTA =
      "hbase.region.server.rpc.minimum.scan.time.limit.delta";
  /**
   * Default value of {@link RSRpcServices#REGION_SERVER_RPC_MINIMUM_SCAN_TIME_LIMIT_DELTA}
   */
  private static final long DEFAULT_REGION_SERVER_RPC_MINIMUM_SCAN_TIME_LIMIT_DELTA = 10;

  // Request counter. (Includes requests that are not serviced by regions.)
  final LongAdder requestCount = new LongAdder();

  // Request counter for rpc get
  final LongAdder rpcGetRequestCount = new LongAdder();

  // Request counter for rpc scan
  final LongAdder rpcScanRequestCount = new LongAdder();

  // Request counter for rpc multi
  final LongAdder rpcMultiRequestCount = new LongAdder();

  // Request counter for rpc mutate
  final LongAdder rpcMutateRequestCount = new LongAdder();

  // Server to handle client requests.
  final RpcServerInterface rpcServer;
  final InetSocketAddress isa;

  private final HRegionServer regionServer;
  private final long maxScannerResultSize;

  // The reference to the priority extraction function
  private final PriorityFunction priority;

  private final AtomicLong scannerIdGen = new AtomicLong(0L);
  private final ConcurrentMap<String, RegionScannerHolder> scanners = new ConcurrentHashMap<>();

  /**
   * The lease timeout period for client scanners (milliseconds).
   */
  private final int scannerLeaseTimeoutPeriod;

  /**
   * The RPC timeout period (milliseconds)
   */
  private final int rpcTimeout;

  /**
   * The minimum allowable delta to use for the scan limit
   */
  private final long minimumScanTimeLimitDelta;

  final AtomicBoolean clearCompactionQueues = new AtomicBoolean(false);

  /**
   * An Rpc callback for closing a RegionScanner.
   */
  private static final class RegionScannerCloseCallBack implements RpcCallback {

    private final RegionScanner scanner;

    public RegionScannerCloseCallBack(RegionScanner scanner) {
      this.scanner = scanner;
    }

    @Override
    public void run() throws IOException {
      this.scanner.close();
    }
  }

  /**
   * An Rpc callback for doing shipped() call on a RegionScanner.
   */
  private class RegionScannerShippedCallBack implements RpcCallback {

    private final String scannerName;
    private final RegionScanner scanner;
    private final Lease lease;

    public RegionScannerShippedCallBack(String scannerName, RegionScanner scanner, Lease lease) {
      this.scannerName = scannerName;
      this.scanner = scanner;
      this.lease = lease;
    }

    @Override
    public void run() throws IOException {
      this.scanner.shipped();
      // We're done. On way out re-add the above removed lease. The lease was temp removed for this
      // Rpc call and we are at end of the call now. Time to add it back.
      if (scanners.containsKey(scannerName)) {
        if (lease != null) regionServer.leases.addLease(lease);
      }
    }
  }

  /**
   * An RpcCallBack that creates a list of scanners that needs to perform callBack operation on
   * completion of multiGets.
   */
   static class RegionScannersCloseCallBack implements RpcCallback {
    private final List<RegionScanner> scanners = new ArrayList<>();

    public void addScanner(RegionScanner scanner) {
      this.scanners.add(scanner);
    }

    @Override
    public void run() {
      for (RegionScanner scanner : scanners) {
        try {
          scanner.close();
        } catch (IOException e) {
          LOG.error("Exception while closing the scanner " + scanner, e);
        }
      }
    }
  }

  /**
   * Holder class which holds the RegionScanner, nextCallSeq and RpcCallbacks together.
   */
  private static final class RegionScannerHolder {

    private final AtomicLong nextCallSeq = new AtomicLong(0);
    private final String scannerName;
    private final RegionScanner s;
    private final Region r;
    private final RpcCallback closeCallBack;
    private final RpcCallback shippedCallback;
    private byte[] rowOfLastPartialResult;

    public RegionScannerHolder(String scannerName, RegionScanner s, Region r,
        RpcCallback closeCallBack, RpcCallback shippedCallback) {
      this.scannerName = scannerName;
      this.s = s;
      this.r = r;
      this.closeCallBack = closeCallBack;
      this.shippedCallback = shippedCallback;
    }

    public long getNextCallSeq() {
      return nextCallSeq.get();
    }

    public boolean incNextCallSeq(long currentSeq) {
      // Use CAS to prevent multiple scan request running on the same scanner.
      return nextCallSeq.compareAndSet(currentSeq, currentSeq + 1);
    }
  }

  /**
   * Instantiated as a scanner lease. If the lease times out, the scanner is
   * closed
   */
  private class ScannerListener implements LeaseListener {
    private final String scannerName;

    ScannerListener(final String n) {
      this.scannerName = n;
    }

    @Override
    public void leaseExpired() {
      RegionScannerHolder rsh = scanners.remove(this.scannerName);
      if (rsh != null) {
        RegionScanner s = rsh.s;
        LOG.info("Scanner " + this.scannerName + " lease expired on region "
          + s.getRegionInfo().getRegionNameAsString());
        Region region = null;
        try {
          region = regionServer.getRegion(s.getRegionInfo().getRegionName());
          if (region != null && region.getCoprocessorHost() != null) {
            region.getCoprocessorHost().preScannerClose(s);
          }
        } catch (IOException e) {
          LOG.error("Closing scanner for " + s.getRegionInfo().getRegionNameAsString(), e);
        } finally {
          try {
            s.close();
            if (region != null && region.getCoprocessorHost() != null) {
              region.getCoprocessorHost().postScannerClose(s);
            }
          } catch (IOException e) {
            LOG.error("Closing scanner for " + s.getRegionInfo().getRegionNameAsString(), e);
          }
        }
      } else {
        LOG.warn("Scanner " + this.scannerName + " lease expired, but no related" +
          " scanner found, hence no chance to close that related scanner!");
      }
    }
  }

  private static ResultOrException getResultOrException(final ClientProtos.Result r,
                                                        final int index){
    return getResultOrException(ResponseConverter.buildActionResult(r), index);
  }

  private static ResultOrException getResultOrException(final Exception e, final int index) {
    return getResultOrException(ResponseConverter.buildActionResult(e), index);
  }

  private static ResultOrException getResultOrException(
      final ResultOrException.Builder builder, final int index) {
    return builder.setIndex(index).build();
  }

  /**
   * Starts the nonce operation for a mutation, if needed.
   * @param mutation Mutation.
   * @param nonceGroup Nonce group from the request.
   * @returns whether to proceed this mutation.
   */
  private boolean startNonceOperation(final MutationProto mutation, long nonceGroup)
      throws IOException {
    if (regionServer.nonceManager == null || !mutation.hasNonce()) return true;
    boolean canProceed = false;
    try {
      canProceed = regionServer.nonceManager.startOperation(
        nonceGroup, mutation.getNonce(), regionServer);
    } catch (InterruptedException ex) {
      throw new InterruptedIOException("Nonce start operation interrupted");
    }
    return canProceed;
  }

  /**
   * Ends nonce operation for a mutation, if needed.
   * @param mutation Mutation.
   * @param nonceGroup Nonce group from the request. Always 0 in initial implementation.
   * @param success Whether the operation for this nonce has succeeded.
   */
  private void endNonceOperation(final MutationProto mutation,
      long nonceGroup, boolean success) {
    if (regionServer.nonceManager != null && mutation.hasNonce()) {
      regionServer.nonceManager.endOperation(nonceGroup, mutation.getNonce(), success);
    }
  }

  private boolean isClientCellBlockSupport(RpcCallContext context) {
    return context != null && context.isClientCellBlockSupported();
  }

  private void addResult(final MutateResponse.Builder builder, final Result result,
      final HBaseRpcController rpcc, boolean clientCellBlockSupported) {
    if (result == null) return;
    if (clientCellBlockSupported) {
      builder.setResult(ProtobufUtil.toResultNoData(result));
      rpcc.setCellScanner(result.cellScanner());
    } else {
      ClientProtos.Result pbr = ProtobufUtil.toResult(result);
      builder.setResult(pbr);
    }
  }

  private void addResults(ScanResponse.Builder builder, List<Result> results,
      HBaseRpcController controller, boolean isDefaultRegion, boolean clientCellBlockSupported) {
    builder.setStale(!isDefaultRegion);
    if (results.isEmpty()) {
      return;
    }
    if (clientCellBlockSupported) {
      for (Result res : results) {
        builder.addCellsPerResult(res.size());
        builder.addPartialFlagPerResult(res.mayHaveMoreCellsInRow());
      }
      controller.setCellScanner(CellUtil.createCellScanner(results));
    } else {
      for (Result res : results) {
        ClientProtos.Result pbr = ProtobufUtil.toResult(res);
        builder.addResults(pbr);
      }
    }
  }

  /**
   * Mutate a list of rows atomically.
   *
   * @param region
   * @param actions
   * @param cellScanner if non-null, the mutation data -- the Cell content.
   * @throws IOException
   */
  private void mutateRows(final Region region,
      final List<ClientProtos.Action> actions,
      final CellScanner cellScanner, RegionActionResult.Builder builder) throws IOException {
    if (!region.getRegionInfo().isMetaTable()) {
      regionServer.cacheFlusher.reclaimMemStoreMemory();
    }
    RowMutations rm = null;
    int i = 0;
    ClientProtos.ResultOrException.Builder resultOrExceptionOrBuilder =
        ClientProtos.ResultOrException.newBuilder();
    for (ClientProtos.Action action: actions) {
      if (action.hasGet()) {
        throw new DoNotRetryIOException("Atomic put and/or delete only, not a Get=" +
          action.getGet());
      }
      MutationType type = action.getMutation().getMutateType();
      if (rm == null) {
        rm = new RowMutations(action.getMutation().getRow().toByteArray(), actions.size());
      }
      switch (type) {
        case PUT:
          Put put = ProtobufUtil.toPut(action.getMutation(), cellScanner);
          checkCellSizeLimit(region, put);
          rm.add(put);
          break;
        case DELETE:
          rm.add(ProtobufUtil.toDelete(action.getMutation(), cellScanner));
          break;
        default:
          throw new DoNotRetryIOException("Atomic put and/or delete only, not " + type.name());
      }
      // To unify the response format with doNonAtomicRegionMutation and read through client's
      // AsyncProcess we have to add an empty result instance per operation
      resultOrExceptionOrBuilder.clear();
      resultOrExceptionOrBuilder.setIndex(i++);
      builder.addResultOrException(
          resultOrExceptionOrBuilder.build());
    }
    region.mutateRow(rm);
  }

  /**
   * Mutate a list of rows atomically.
   *
   * @param region
   * @param actions
   * @param cellScanner if non-null, the mutation data -- the Cell content.
   * @param row
   * @param family
   * @param qualifier
   * @param compareOp
   * @param comparator @throws IOException
   */
  private boolean checkAndRowMutate(final Region region, final List<ClientProtos.Action> actions,
      final CellScanner cellScanner, byte[] row, byte[] family, byte[] qualifier,
      CompareOp compareOp, ByteArrayComparable comparator,
                                    RegionActionResult.Builder builder) throws IOException {
    if (!region.getRegionInfo().isMetaTable()) {
      regionServer.cacheFlusher.reclaimMemStoreMemory();
    }
    RowMutations rm = null;
    int i = 0;
    ClientProtos.ResultOrException.Builder resultOrExceptionOrBuilder =
        ClientProtos.ResultOrException.newBuilder();
    for (ClientProtos.Action action: actions) {
      if (action.hasGet()) {
        throw new DoNotRetryIOException("Atomic put and/or delete only, not a Get=" +
            action.getGet());
      }
      MutationType type = action.getMutation().getMutateType();
      if (rm == null) {
        rm = new RowMutations(action.getMutation().getRow().toByteArray(), actions.size());
      }
      switch (type) {
        case PUT:
          Put put = ProtobufUtil.toPut(action.getMutation(), cellScanner);
          checkCellSizeLimit(region, put);
          rm.add(put);
          break;
        case DELETE:
          rm.add(ProtobufUtil.toDelete(action.getMutation(), cellScanner));
          break;
        default:
          throw new DoNotRetryIOException("Atomic put and/or delete only, not " + type.name());
      }
      // To unify the response format with doNonAtomicRegionMutation and read through client's
      // AsyncProcess we have to add an empty result instance per operation
      resultOrExceptionOrBuilder.clear();
      resultOrExceptionOrBuilder.setIndex(i++);
      builder.addResultOrException(
          resultOrExceptionOrBuilder.build());
    }
    return region.checkAndRowMutate(row, family, qualifier, compareOp,
        comparator, rm, Boolean.TRUE);
  }

  /**
   * Execute an append mutation.
   *
   * @param region
   * @param m
   * @param cellScanner
   * @return result to return to client if default operation should be
   * bypassed as indicated by RegionObserver, null otherwise
   * @throws IOException
   */
  private Result append(final Region region, final OperationQuota quota,
      final MutationProto mutation, final CellScanner cellScanner, long nonceGroup)
      throws IOException {
    long before = EnvironmentEdgeManager.currentTime();
    Append append = ProtobufUtil.toAppend(mutation, cellScanner);
    checkCellSizeLimit(region, append);
    quota.addMutation(append);
    Result r = null;
    if (region.getCoprocessorHost() != null) {
      r = region.getCoprocessorHost().preAppend(append);
    }
    if (r == null) {
      boolean canProceed = startNonceOperation(mutation, nonceGroup);
      boolean success = false;
      try {
        long nonce = mutation.hasNonce() ? mutation.getNonce() : HConstants.NO_NONCE;
        if (canProceed) {
          r = region.append(append, nonceGroup, nonce);
        } else {
          // convert duplicate append to get
          List<Cell> results = region.get(ProtobufUtil.toGet(mutation, cellScanner), false,
            nonceGroup, nonce);
          r = Result.create(results);
        }
        success = true;
      } finally {
        if (canProceed) {
          endNonceOperation(mutation, nonceGroup, success);
        }
      }
      if (region.getCoprocessorHost() != null) {
        region.getCoprocessorHost().postAppend(append, r);
      }
    }
    if (regionServer.metricsRegionServer != null) {
      regionServer.metricsRegionServer.updateAppend(
        EnvironmentEdgeManager.currentTime() - before);
    }
    return r;
  }

  /**
   * Execute an increment mutation.
   *
   * @param region
   * @param mutation
   * @return the Result
   * @throws IOException
   */
  private Result increment(final Region region, final OperationQuota quota,
      final MutationProto mutation, final CellScanner cells, long nonceGroup)
      throws IOException {
    long before = EnvironmentEdgeManager.currentTime();
    Increment increment = ProtobufUtil.toIncrement(mutation, cells);
    checkCellSizeLimit(region, increment);
    quota.addMutation(increment);
    Result r = null;
    if (region.getCoprocessorHost() != null) {
      r = region.getCoprocessorHost().preIncrement(increment);
    }
    if (r == null) {
      boolean canProceed = startNonceOperation(mutation, nonceGroup);
      boolean success = false;
      try {
        long nonce = mutation.hasNonce() ? mutation.getNonce() : HConstants.NO_NONCE;
        if (canProceed) {
          r = region.increment(increment, nonceGroup, nonce);
        } else {
          // convert duplicate increment to get
          List<Cell> results = region.get(ProtobufUtil.toGet(mutation, cells), false, nonceGroup,
            nonce);
          r = Result.create(results);
        }
        success = true;
      } finally {
        if (canProceed) {
          endNonceOperation(mutation, nonceGroup, success);
        }
      }
      if (region.getCoprocessorHost() != null) {
        r = region.getCoprocessorHost().postIncrement(increment, r);
      }
    }
    if (regionServer.metricsRegionServer != null) {
      regionServer.metricsRegionServer.updateIncrement(
        EnvironmentEdgeManager.currentTime() - before);
    }
    return r;
  }

  /**
   * Run through the regionMutation <code>rm</code> and per Mutation, do the work, and then when
   * done, add an instance of a {@link ResultOrException} that corresponds to each Mutation.
   * @param region
   * @param actions
   * @param cellScanner
   * @param builder
   * @param cellsToReturn  Could be null. May be allocated in this method.  This is what this
   * method returns as a 'result'.
   * @param closeCallBack the callback to be used with multigets
   * @param context the current RpcCallContext
   * @return Return the <code>cellScanner</code> passed
   */
  private List<CellScannable> doNonAtomicRegionMutation(final Region region,
      final OperationQuota quota, final RegionAction actions, final CellScanner cellScanner,
      final RegionActionResult.Builder builder, List<CellScannable> cellsToReturn, long nonceGroup,
      final RegionScannersCloseCallBack closeCallBack, RpcCallContext context) {
    // Gather up CONTIGUOUS Puts and Deletes in this mutations List.  Idea is that rather than do
    // one at a time, we instead pass them in batch.  Be aware that the corresponding
    // ResultOrException instance that matches each Put or Delete is then added down in the
    // doBatchOp call.  We should be staying aligned though the Put and Delete are deferred/batched
    List<ClientProtos.Action> mutations = null;
    long maxQuotaResultSize = Math.min(maxScannerResultSize, quota.getReadAvailable());
    IOException sizeIOE = null;
    Object lastBlock = null;
    ClientProtos.ResultOrException.Builder resultOrExceptionBuilder = ResultOrException.newBuilder();
    boolean hasResultOrException = false;
    for (ClientProtos.Action action : actions.getActionList()) {
      hasResultOrException = false;
      resultOrExceptionBuilder.clear();
      try {
        Result r = null;

        if (context != null
            && context.isRetryImmediatelySupported()
            && (context.getResponseCellSize() > maxQuotaResultSize
              || context.getResponseBlockSize() + context.getResponseExceptionSize()
              > maxQuotaResultSize)) {

          // We're storing the exception since the exception and reason string won't
          // change after the response size limit is reached.
          if (sizeIOE == null ) {
            // We don't need the stack un-winding do don't throw the exception.
            // Throwing will kill the JVM's JIT.
            //
            // Instead just create the exception and then store it.
            sizeIOE = new MultiActionResultTooLarge("Max size exceeded"
                + " CellSize: " + context.getResponseCellSize()
                + " BlockSize: " + context.getResponseBlockSize());

            // Only report the exception once since there's only one request that
            // caused the exception. Otherwise this number will dominate the exceptions count.
            rpcServer.getMetrics().exception(sizeIOE);
          }

          // Now that there's an exception is known to be created
          // use it for the response.
          //
          // This will create a copy in the builder.
          hasResultOrException = true;
          NameBytesPair pair = ResponseConverter.buildException(sizeIOE);
          resultOrExceptionBuilder.setException(pair);
          context.incrementResponseExceptionSize(pair.getSerializedSize());
          resultOrExceptionBuilder.setIndex(action.getIndex());
          builder.addResultOrException(resultOrExceptionBuilder.build());
          if (cellScanner != null) {
            skipCellsForMutation(action, cellScanner);
          }
          continue;
        }
        if (action.hasGet()) {
          long before = EnvironmentEdgeManager.currentTime();
          try {
            Get get = ProtobufUtil.toGet(action.getGet());
            if (context != null) {
              r = get(get, ((HRegion) region), closeCallBack, context);
            } else {
              r = region.get(get);
            }
          } finally {
            if (regionServer.metricsRegionServer != null) {
              regionServer.metricsRegionServer.updateGet(
                EnvironmentEdgeManager.currentTime() - before);
            }
          }
        } else if (action.hasServiceCall()) {
          hasResultOrException = true;
          try {
            com.google.protobuf.Message result =
                execServiceOnRegion(region, action.getServiceCall());
            ClientProtos.CoprocessorServiceResult.Builder serviceResultBuilder =
                ClientProtos.CoprocessorServiceResult.newBuilder();
            resultOrExceptionBuilder.setServiceResult(
                serviceResultBuilder.setValue(
                  serviceResultBuilder.getValueBuilder()
                    .setName(result.getClass().getName())
                    // TODO: Copy!!!
                    .setValue(UnsafeByteOperations.unsafeWrap(result.toByteArray()))));
          } catch (IOException ioe) {
            rpcServer.getMetrics().exception(ioe);
            NameBytesPair pair = ResponseConverter.buildException(ioe);
            resultOrExceptionBuilder.setException(pair);
            context.incrementResponseExceptionSize(pair.getSerializedSize());
          }
        } else if (action.hasMutation()) {
          MutationType type = action.getMutation().getMutateType();
          if (type != MutationType.PUT && type != MutationType.DELETE && mutations != null &&
              !mutations.isEmpty()) {
            // Flush out any Puts or Deletes already collected.
            doBatchOp(builder, region, quota, mutations, cellScanner);
            mutations.clear();
          }
          switch (type) {
            case APPEND:
              r = append(region, quota, action.getMutation(), cellScanner, nonceGroup);
              break;
            case INCREMENT:
              r = increment(region, quota, action.getMutation(), cellScanner, nonceGroup);
              break;
            case PUT:
            case DELETE:
              // Collect the individual mutations and apply in a batch
              if (mutations == null) {
                mutations = new ArrayList<>(actions.getActionCount());
              }
              mutations.add(action);
              break;
            default:
              throw new DoNotRetryIOException("Unsupported mutate type: " + type.name());
          }
        } else {
          throw new HBaseIOException("Unexpected Action type");
        }
        if (r != null) {
          ClientProtos.Result pbResult = null;
          if (isClientCellBlockSupport(context)) {
            pbResult = ProtobufUtil.toResultNoData(r);
            //  Hard to guess the size here.  Just make a rough guess.
            if (cellsToReturn == null) {
              cellsToReturn = new ArrayList<>();
            }
            cellsToReturn.add(r);
          } else {
            pbResult = ProtobufUtil.toResult(r);
          }
          lastBlock = addSize(context, r, lastBlock);
          hasResultOrException = true;
          resultOrExceptionBuilder.setResult(pbResult);
        }
        // Could get to here and there was no result and no exception.  Presumes we added
        // a Put or Delete to the collecting Mutations List for adding later.  In this
        // case the corresponding ResultOrException instance for the Put or Delete will be added
        // down in the doBatchOp method call rather than up here.
      } catch (IOException ie) {
        rpcServer.getMetrics().exception(ie);
        hasResultOrException = true;
        NameBytesPair pair = ResponseConverter.buildException(ie);
        resultOrExceptionBuilder.setException(pair);
        context.incrementResponseExceptionSize(pair.getSerializedSize());
      }
      if (hasResultOrException) {
        // Propagate index.
        resultOrExceptionBuilder.setIndex(action.getIndex());
        builder.addResultOrException(resultOrExceptionBuilder.build());
      }
    }
    // Finish up any outstanding mutations
    if (mutations != null && !mutations.isEmpty()) {
      doBatchOp(builder, region, quota, mutations, cellScanner);
    }
    return cellsToReturn;
  }

  private void checkCellSizeLimit(final Region region, final Mutation m) throws IOException {
    if (!(region instanceof HRegion)) {
      return;
    }
    HRegion r = (HRegion)region;
    if (r.maxCellSize > 0) {
      CellScanner cells = m.cellScanner();
      while (cells.advance()) {
        int size = CellUtil.estimatedSerializedSizeOf(cells.current());
        if (size > r.maxCellSize) {
          String msg = "Cell with size " + size + " exceeds limit of " + r.maxCellSize + " bytes";
          if (LOG.isDebugEnabled()) {
            LOG.debug(msg);
          }
          throw new DoNotRetryIOException(msg);
        }
      }
    }
  }

  /**
   * Execute a list of Put/Delete mutations.
   *
   * @param builder
   * @param region
   * @param mutations
   */
  private void doBatchOp(final RegionActionResult.Builder builder, final Region region,
      final OperationQuota quota, final List<ClientProtos.Action> mutations,
      final CellScanner cells) {
    Mutation[] mArray = new Mutation[mutations.size()];
    long before = EnvironmentEdgeManager.currentTime();
    boolean batchContainsPuts = false, batchContainsDelete = false;
    try {
      /** HBASE-17924
       * mutationActionMap is a map to map the relation between mutations and actions
       * since mutation array may have been reoredered.In order to return the right
       * result or exception to the corresponding actions, We need to know which action
       * is the mutation belong to. We can't sort ClientProtos.Action array, since they
       * are bonded to cellscanners.
       */
      Map<Mutation, ClientProtos.Action> mutationActionMap = new HashMap<Mutation, ClientProtos.Action>();
      int i = 0;
      for (ClientProtos.Action action: mutations) {
        MutationProto m = action.getMutation();
        Mutation mutation;
        if (m.getMutateType() == MutationType.PUT) {
          mutation = ProtobufUtil.toPut(m, cells);
          batchContainsPuts = true;
        } else {
          mutation = ProtobufUtil.toDelete(m, cells);
          batchContainsDelete = true;
        }
        mutationActionMap.put(mutation, action);
        mArray[i++] = mutation;
        checkCellSizeLimit(region, mutation);
        quota.addMutation(mutation);
      }

      if (!region.getRegionInfo().isMetaTable()) {
        regionServer.cacheFlusher.reclaimMemStoreMemory();
      }

      // HBASE-17924
      // sort to improve lock efficiency
      Arrays.sort(mArray);

      OperationStatus[] codes = region.batchMutate(mArray, HConstants.NO_NONCE,
        HConstants.NO_NONCE);
      for (i = 0; i < codes.length; i++) {
        Mutation currentMutation = mArray[i];
        ClientProtos.Action currentAction = mutationActionMap.get(currentMutation);
        int index = currentAction.getIndex();
        Exception e = null;
        switch (codes[i].getOperationStatusCode()) {
          case BAD_FAMILY:
            e = new NoSuchColumnFamilyException(codes[i].getExceptionMsg());
            builder.addResultOrException(getResultOrException(e, index));
            break;

          case SANITY_CHECK_FAILURE:
            e = new FailedSanityCheckException(codes[i].getExceptionMsg());
            builder.addResultOrException(getResultOrException(e, index));
            break;

          default:
            e = new DoNotRetryIOException(codes[i].getExceptionMsg());
            builder.addResultOrException(getResultOrException(e, index));
            break;

          case SUCCESS:
            builder.addResultOrException(getResultOrException(
              ClientProtos.Result.getDefaultInstance(), index));
            break;
        }
      }
    } catch (IOException ie) {
      for (int i = 0; i < mutations.size(); i++) {
        builder.addResultOrException(getResultOrException(ie, mutations.get(i).getIndex()));
      }
    }
    if (regionServer.metricsRegionServer != null) {
      long after = EnvironmentEdgeManager.currentTime();
      if (batchContainsPuts) {
        regionServer.metricsRegionServer.updatePut(after - before);
      }
      if (batchContainsDelete) {
        regionServer.metricsRegionServer.updateDelete(after - before);
      }
    }
  }

  /**
   * Execute a list of Put/Delete mutations. The function returns OperationStatus instead of
   * constructing MultiResponse to save a possible loop if caller doesn't need MultiResponse.
   * @param region
   * @param mutations
   * @param replaySeqId
   * @return an array of OperationStatus which internally contains the OperationStatusCode and the
   *         exceptionMessage if any
   * @throws IOException
   */
  private OperationStatus [] doReplayBatchOp(final Region region,
      final List<WALSplitter.MutationReplay> mutations, long replaySeqId) throws IOException {
    long before = EnvironmentEdgeManager.currentTime();
    boolean batchContainsPuts = false, batchContainsDelete = false;
    try {
      for (Iterator<WALSplitter.MutationReplay> it = mutations.iterator(); it.hasNext();) {
        WALSplitter.MutationReplay m = it.next();

        if (m.type == MutationType.PUT) {
          batchContainsPuts = true;
        } else {
          batchContainsDelete = true;
        }

        NavigableMap<byte[], List<Cell>> map = m.mutation.getFamilyCellMap();
        List<Cell> metaCells = map.get(WALEdit.METAFAMILY);
        if (metaCells != null && !metaCells.isEmpty()) {
          for (Cell metaCell : metaCells) {
            CompactionDescriptor compactionDesc = WALEdit.getCompaction(metaCell);
            boolean isDefaultReplica = RegionReplicaUtil.isDefaultReplica(region.getRegionInfo());
            HRegion hRegion = (HRegion)region;
            if (compactionDesc != null) {
              // replay the compaction. Remove the files from stores only if we are the primary
              // region replica (thus own the files)
              hRegion.replayWALCompactionMarker(compactionDesc, !isDefaultReplica, isDefaultReplica,
                replaySeqId);
              continue;
            }
            FlushDescriptor flushDesc = WALEdit.getFlushDescriptor(metaCell);
            if (flushDesc != null && !isDefaultReplica) {
              hRegion.replayWALFlushMarker(flushDesc, replaySeqId);
              continue;
            }
            RegionEventDescriptor regionEvent = WALEdit.getRegionEventDescriptor(metaCell);
            if (regionEvent != null && !isDefaultReplica) {
              hRegion.replayWALRegionEventMarker(regionEvent);
              continue;
            }
            BulkLoadDescriptor bulkLoadEvent = WALEdit.getBulkLoadDescriptor(metaCell);
            if (bulkLoadEvent != null) {
              hRegion.replayWALBulkLoadEventMarker(bulkLoadEvent);
              continue;
            }
          }
          it.remove();
        }
      }
      requestCount.add(mutations.size());
      if (!region.getRegionInfo().isMetaTable()) {
        regionServer.cacheFlusher.reclaimMemStoreMemory();
      }
      return region.batchReplay(mutations.toArray(
        new WALSplitter.MutationReplay[mutations.size()]), replaySeqId);
    } finally {
      if (regionServer.metricsRegionServer != null) {
        long after = EnvironmentEdgeManager.currentTime();
          if (batchContainsPuts) {
          regionServer.metricsRegionServer.updatePut(after - before);
        }
        if (batchContainsDelete) {
          regionServer.metricsRegionServer.updateDelete(after - before);
        }
      }
    }
  }

  private void closeAllScanners() {
    // Close any outstanding scanners. Means they'll get an UnknownScanner
    // exception next time they come in.
    for (Map.Entry<String, RegionScannerHolder> e : scanners.entrySet()) {
      try {
        e.getValue().s.close();
      } catch (IOException ioe) {
        LOG.warn("Closing scanner " + e.getKey(), ioe);
      }
    }
  }

  public RSRpcServices(HRegionServer rs) throws IOException {
    regionServer = rs;

    RpcSchedulerFactory rpcSchedulerFactory;
    try {
      Class<?> rpcSchedulerFactoryClass = rs.conf.getClass(
          REGION_SERVER_RPC_SCHEDULER_FACTORY_CLASS,
          SimpleRpcSchedulerFactory.class);
      rpcSchedulerFactory = ((RpcSchedulerFactory) rpcSchedulerFactoryClass.newInstance());
    } catch (InstantiationException e) {
      throw new IllegalArgumentException(e);
    } catch (IllegalAccessException e) {
      throw new IllegalArgumentException(e);
    }
    // Server to handle client requests.
    InetSocketAddress initialIsa;
    InetSocketAddress bindAddress;
    if(this instanceof MasterRpcServices) {
      String hostname = getHostname(rs.conf, true);
      int port = rs.conf.getInt(HConstants.MASTER_PORT, HConstants.DEFAULT_MASTER_PORT);
      // Creation of a HSA will force a resolve.
      initialIsa = new InetSocketAddress(hostname, port);
      bindAddress = new InetSocketAddress(rs.conf.get("hbase.master.ipc.address", hostname), port);
    } else {
      String hostname = getHostname(rs.conf, false);
      int port = rs.conf.getInt(HConstants.REGIONSERVER_PORT,
        HConstants.DEFAULT_REGIONSERVER_PORT);
      // Creation of a HSA will force a resolve.
      initialIsa = new InetSocketAddress(hostname, port);
      bindAddress = new InetSocketAddress(
        rs.conf.get("hbase.regionserver.ipc.address", hostname), port);
    }
    if (initialIsa.getAddress() == null) {
      throw new IllegalArgumentException("Failed resolve of " + initialIsa);
    }
    priority = createPriority();
    String name = rs.getProcessName() + "/" + initialIsa.toString();
    // Set how many times to retry talking to another server over Connection.
    ConnectionUtils.setServerSideHConnectionRetriesConfig(rs.conf, name, LOG);
    try {
      rpcServer = RpcServerFactory.createRpcServer(rs, name, getServices(),
          bindAddress, // use final bindAddress for this server.
          rs.conf,
          rpcSchedulerFactory.create(rs.conf, this, rs));
      rpcServer.setRsRpcServices(this);
    } catch (BindException be) {
      String configName = (this instanceof MasterRpcServices) ? HConstants.MASTER_PORT :
          HConstants.REGIONSERVER_PORT;
      throw new IOException(be.getMessage() + ". To switch ports use the '" + configName +
          "' configuration property.", be.getCause() != null ? be.getCause() : be);
    }

    scannerLeaseTimeoutPeriod = rs.conf.getInt(
      HConstants.HBASE_CLIENT_SCANNER_TIMEOUT_PERIOD,
      HConstants.DEFAULT_HBASE_CLIENT_SCANNER_TIMEOUT_PERIOD);
    maxScannerResultSize = rs.conf.getLong(
      HConstants.HBASE_SERVER_SCANNER_MAX_RESULT_SIZE_KEY,
      HConstants.DEFAULT_HBASE_SERVER_SCANNER_MAX_RESULT_SIZE);
    rpcTimeout = rs.conf.getInt(
      HConstants.HBASE_RPC_TIMEOUT_KEY,
      HConstants.DEFAULT_HBASE_RPC_TIMEOUT);
    minimumScanTimeLimitDelta = rs.conf.getLong(
      REGION_SERVER_RPC_MINIMUM_SCAN_TIME_LIMIT_DELTA,
      DEFAULT_REGION_SERVER_RPC_MINIMUM_SCAN_TIME_LIMIT_DELTA);

    InetSocketAddress address = rpcServer.getListenerAddress();
    if (address == null) {
      throw new IOException("Listener channel is closed");
    }
    // Set our address, however we need the final port that was given to rpcServer
    isa = new InetSocketAddress(initialIsa.getHostName(), address.getPort());
    rpcServer.setErrorHandler(this);
    rs.setName(name);
  }

  @Override
  public void onConfigurationChange(Configuration newConf) {
    if (rpcServer instanceof ConfigurationObserver) {
      ((ConfigurationObserver)rpcServer).onConfigurationChange(newConf);
    }
  }

  protected PriorityFunction createPriority() {
    return new AnnotationReadingPriorityFunction(this);
  }

  public static String getHostname(Configuration conf, boolean isMaster)
      throws UnknownHostException {
    String hostname = conf.get(isMaster? HRegionServer.MASTER_HOSTNAME_KEY :
      HRegionServer.RS_HOSTNAME_KEY);
    if (hostname == null || hostname.isEmpty()) {
      String masterOrRS = isMaster ? "master" : "regionserver";
      return Strings.domainNamePointerToHostName(DNS.getDefaultHost(
        conf.get("hbase." + masterOrRS + ".dns.interface", "default"),
        conf.get("hbase." + masterOrRS + ".dns.nameserver", "default")));
    } else {
      LOG.info("hostname is configured to be " + hostname);
      return hostname;
    }
  }

  @VisibleForTesting
  public int getScannersCount() {
    return scanners.size();
  }

  public
  RegionScanner getScanner(long scannerId) {
    String scannerIdString = Long.toString(scannerId);
    RegionScannerHolder scannerHolder = scanners.get(scannerIdString);
    if (scannerHolder != null) {
      return scannerHolder.s;
    }
    return null;
  }

  public String getScanDetailsWithId(long scannerId) {
    RegionScanner scanner = getScanner(scannerId);
    if (scanner == null) {
      return null;
    }
    StringBuilder builder = new StringBuilder();
    builder.append("table: ").append(scanner.getRegionInfo().getTable().getNameAsString());
    builder.append(" region: ").append(scanner.getRegionInfo().getRegionNameAsString());
    return builder.toString();
  }

  /**
   * Get the vtime associated with the scanner.
   * Currently the vtime is the number of "next" calls.
   */
  long getScannerVirtualTime(long scannerId) {
    String scannerIdString = Long.toString(scannerId);
    RegionScannerHolder scannerHolder = scanners.get(scannerIdString);
    if (scannerHolder != null) {
      return scannerHolder.getNextCallSeq();
    }
    return 0L;
  }

  /**
   * Method to account for the size of retained cells and retained data blocks.
   * @return an object that represents the last referenced block from this response.
   */
  Object addSize(RpcCallContext context, Result r, Object lastBlock) {
    if (context != null && r != null && !r.isEmpty()) {
      for (Cell c : r.rawCells()) {
        context.incrementResponseCellSize(CellUtil.estimatedSerializedSizeOf(c));

        // Since byte buffers can point all kinds of crazy places it's harder to keep track
        // of which blocks are kept alive by what byte buffer.
        // So we make a guess.
        if (c instanceof ByteBufferCell) {
          ByteBufferCell bbCell = (ByteBufferCell) c;
          ByteBuffer bb = bbCell.getValueByteBuffer();
          if (bb != lastBlock) {
            context.incrementResponseBlockSize(bb.capacity());
            lastBlock = bb;
          }
        } else {
          // We're using the last block being the same as the current block as
          // a proxy for pointing to a new block. This won't be exact.
          // If there are multiple gets that bounce back and forth
          // Then it's possible that this will over count the size of
          // referenced blocks. However it's better to over count and
          // use two rpcs than to OOME the regionserver.
          byte[] valueArray = c.getValueArray();
          if (valueArray != lastBlock) {
            context.incrementResponseBlockSize(valueArray.length);
            lastBlock = valueArray;
          }
        }

      }
    }
    return lastBlock;
  }

  private RegionScannerHolder addScanner(String scannerName, RegionScanner s, Region r)
      throws LeaseStillHeldException {
    Lease lease = regionServer.leases.createLease(scannerName, this.scannerLeaseTimeoutPeriod,
      new ScannerListener(scannerName));
    RpcCallback shippedCallback = new RegionScannerShippedCallBack(scannerName, s, lease);
    RpcCallback closeCallback;
    if (s instanceof RpcCallback) {
      closeCallback = (RpcCallback) s;
    } else {
      closeCallback = new RegionScannerCloseCallBack(s);
    }
    RegionScannerHolder rsh =
        new RegionScannerHolder(scannerName, s, r, closeCallback, shippedCallback);
    RegionScannerHolder existing = scanners.putIfAbsent(scannerName, rsh);
    assert existing == null : "scannerId must be unique within regionserver's whole lifecycle!";
    return rsh;
  }

  /**
   * Find the HRegion based on a region specifier
   *
   * @param regionSpecifier the region specifier
   * @return the corresponding region
   * @throws IOException if the specifier is not null,
   *    but failed to find the region
   */
  @VisibleForTesting
  public Region getRegion(
      final RegionSpecifier regionSpecifier) throws IOException {
    ByteString value = regionSpecifier.getValue();
    RegionSpecifierType type = regionSpecifier.getType();
    switch (type) {
      case REGION_NAME:
        byte[] regionName = value.toByteArray();
        String encodedRegionName = HRegionInfo.encodeRegionName(regionName);
        return regionServer.getRegionByEncodedName(regionName, encodedRegionName);
      case ENCODED_REGION_NAME:
        return regionServer.getRegionByEncodedName(value.toStringUtf8());
      default:
        throw new DoNotRetryIOException(
          "Unsupported region specifier type: " + type);
    }
  }

  @VisibleForTesting
  public PriorityFunction getPriority() {
    return priority;
  }

  @VisibleForTesting
  public Configuration getConfiguration() {
    return regionServer.getConfiguration();
  }

  private RegionServerQuotaManager getQuotaManager() {
    return regionServer.getRegionServerQuotaManager();
  }

  void start() {
    rpcServer.start();
  }

  void stop() {
    closeAllScanners();
    rpcServer.stop();
  }

  /**
   * Called to verify that this server is up and running.
   *
   * @throws IOException
   */
  protected void checkOpen() throws IOException {
    if (regionServer.isAborted()) {
      throw new RegionServerAbortedException("Server " + regionServer.serverName + " aborting");
    }
    if (regionServer.isStopped()) {
      throw new RegionServerStoppedException("Server " + regionServer.serverName + " stopping");
    }
    if (!regionServer.fsOk) {
      throw new RegionServerStoppedException("File system not available");
    }
    if (!regionServer.isOnline()) {
      throw new ServerNotRunningYetException("Server " + regionServer.serverName
          + " is not running yet");
    }
  }

  /**
   * @return list of blocking services and their security info classes that this server supports
   */
  protected List<BlockingServiceAndInterface> getServices() {
    List<BlockingServiceAndInterface> bssi = new ArrayList<>(2);
    bssi.add(new BlockingServiceAndInterface(
      ClientService.newReflectiveBlockingService(this),
      ClientService.BlockingInterface.class));
    bssi.add(new BlockingServiceAndInterface(
      AdminService.newReflectiveBlockingService(this),
      AdminService.BlockingInterface.class));
    return bssi;
  }

  public InetSocketAddress getSocketAddress() {
    return isa;
  }

  @Override
  public int getPriority(RequestHeader header, Message param, User user) {
    return priority.getPriority(header, param, user);
  }

  @Override
  public long getDeadline(RequestHeader header, Message param) {
    return priority.getDeadline(header, param);
  }

  /*
   * Check if an OOME and, if so, abort immediately to avoid creating more objects.
   *
   * @param e
   *
   * @return True if we OOME'd and are aborting.
   */
  @Override
  public boolean checkOOME(final Throwable e) {
    return exitIfOOME(e);
  }

  public static boolean exitIfOOME(final Throwable e ){
    boolean stop = false;
    try {
      if (e instanceof OutOfMemoryError
          || (e.getCause() != null && e.getCause() instanceof OutOfMemoryError)
          || (e.getMessage() != null && e.getMessage().contains(
              "java.lang.OutOfMemoryError"))) {
        stop = true;
        LOG.fatal("Run out of memory; " + RSRpcServices.class.getSimpleName()
          + " will abort itself immediately", e);
      }
    } finally {
      if (stop) {
        Runtime.getRuntime().halt(1);
      }
    }
    return stop;
  }

  /**
   * Close a region on the region server.
   *
   * @param controller the RPC controller
   * @param request the request
   * @throws ServiceException
   */
  @Override
  @QosPriority(priority=HConstants.ADMIN_QOS)
  public CloseRegionResponse closeRegion(final RpcController controller,
      final CloseRegionRequest request) throws ServiceException {
    final ServerName sn = (request.hasDestinationServer() ?
      ProtobufUtil.toServerName(request.getDestinationServer()) : null);

    try {
      checkOpen();
      if (request.hasServerStartCode()) {
        // check that we are the same server that this RPC is intended for.
        long serverStartCode = request.getServerStartCode();
        if (regionServer.serverName.getStartcode() !=  serverStartCode) {
          throw new ServiceException(new DoNotRetryIOException("This RPC was intended for a " +
              "different server with startCode: " + serverStartCode + ", this server is: "
              + regionServer.serverName));
        }
      }
      final String encodedRegionName = ProtobufUtil.getRegionEncodedName(request.getRegion());

      requestCount.increment();
      if (sn == null) {
        LOG.info("Close " + encodedRegionName + " without moving");
      } else {
        LOG.info("Close " + encodedRegionName + ", moving to " + sn);
      }
      boolean closed = regionServer.closeRegion(encodedRegionName, false, sn);
      CloseRegionResponse.Builder builder = CloseRegionResponse.newBuilder().setClosed(closed);
      return builder.build();
    } catch (IOException ie) {
      throw new ServiceException(ie);
    }
  }

  @Override
  @QosPriority(priority=HConstants.ADMIN_QOS)
  public CloseRegionForSplitOrMergeResponse closeRegionForSplitOrMerge(
      final RpcController controller,
      final CloseRegionForSplitOrMergeRequest request) throws ServiceException {
    try {
      checkOpen();

      List<String> encodedRegionNameList = new ArrayList<>();
      for(int i = 0; i < request.getRegionCount(); i++) {
        final String encodedRegionName = ProtobufUtil.getRegionEncodedName(request.getRegion(i));

        // Can be null if we're calling close on a region that's not online
        final Region targetRegion = regionServer.getFromOnlineRegions(encodedRegionName);
        if ((targetRegion != null) && (targetRegion.getCoprocessorHost() != null)) {
          targetRegion.getCoprocessorHost().preClose(false);
          encodedRegionNameList.add(encodedRegionName);
        }
      }
      requestCount.increment();
      LOG.info("Close and offline " + encodedRegionNameList + " regions.");
      boolean closed = regionServer.closeAndOfflineRegionForSplitOrMerge(encodedRegionNameList);
      CloseRegionForSplitOrMergeResponse.Builder builder =
          CloseRegionForSplitOrMergeResponse.newBuilder().setClosed(closed);
      return builder.build();
    } catch (IOException ie) {
      throw new ServiceException(ie);
    }
  }

  /**
   * Compact a region on the region server.
   *
   * @param controller the RPC controller
   * @param request the request
   * @throws ServiceException
   */
  @Override
  @QosPriority(priority=HConstants.ADMIN_QOS)
  public CompactRegionResponse compactRegion(final RpcController controller,
      final CompactRegionRequest request) throws ServiceException {
    try {
      checkOpen();
      requestCount.increment();
      Region region = getRegion(request.getRegion());
      region.startRegionOperation(Operation.COMPACT_REGION);
      LOG.info("Compacting " + region.getRegionInfo().getRegionNameAsString());
      boolean major = false;
      byte [] family = null;
      Store store = null;
      if (request.hasFamily()) {
        family = request.getFamily().toByteArray();
        store = region.getStore(family);
        if (store == null) {
          throw new ServiceException(new DoNotRetryIOException("column family " +
              Bytes.toString(family) + " does not exist in region " +
              region.getRegionInfo().getRegionNameAsString()));
        }
      }
      if (request.hasMajor()) {
        major = request.getMajor();
      }
      if (major) {
        if (family != null) {
          store.triggerMajorCompaction();
        } else {
          region.triggerMajorCompaction();
        }
      }

      String familyLogMsg = (family != null)?" for column family: " + Bytes.toString(family):"";
      if (LOG.isTraceEnabled()) {
        LOG.trace("User-triggered compaction requested for region "
          + region.getRegionInfo().getRegionNameAsString() + familyLogMsg);
      }
      String log = "User-triggered " + (major ? "major " : "") + "compaction" + familyLogMsg;
      if(family != null) {
        regionServer.compactSplitThread.requestCompaction(region, store, log,
          Store.PRIORITY_USER, null, RpcServer.getRequestUser());
      } else {
        regionServer.compactSplitThread.requestCompaction(region, log,
          Store.PRIORITY_USER, null, RpcServer.getRequestUser());
      }
      return CompactRegionResponse.newBuilder().build();
    } catch (IOException ie) {
      throw new ServiceException(ie);
    }
  }

  /**
   * Flush a region on the region server.
   *
   * @param controller the RPC controller
   * @param request the request
   * @throws ServiceException
   */
  @Override
  @QosPriority(priority=HConstants.ADMIN_QOS)
  public FlushRegionResponse flushRegion(final RpcController controller,
      final FlushRegionRequest request) throws ServiceException {
    try {
      checkOpen();
      requestCount.increment();
      Region region = getRegion(request.getRegion());
      LOG.info("Flushing " + region.getRegionInfo().getRegionNameAsString());
      boolean shouldFlush = true;
      if (request.hasIfOlderThanTs()) {
        shouldFlush = region.getEarliestFlushTimeForAllStores() < request.getIfOlderThanTs();
      }
      FlushRegionResponse.Builder builder = FlushRegionResponse.newBuilder();
      if (shouldFlush) {
        boolean writeFlushWalMarker =  request.hasWriteFlushWalMarker() ?
            request.getWriteFlushWalMarker() : false;
        // Go behind the curtain so we can manage writing of the flush WAL marker
        HRegion.FlushResultImpl flushResult = (HRegion.FlushResultImpl)
            ((HRegion)region).flushcache(true, writeFlushWalMarker);
        boolean compactionNeeded = flushResult.isCompactionNeeded();
        if (compactionNeeded) {
          regionServer.compactSplitThread.requestSystemCompaction(region,
            "Compaction through user triggered flush");
        }
        builder.setFlushed(flushResult.isFlushSucceeded());
        builder.setWroteFlushWalMarker(flushResult.wroteFlushWalMarker);
      }
      builder.setLastFlushTime(region.getEarliestFlushTimeForAllStores());
      return builder.build();
    } catch (DroppedSnapshotException ex) {
      // Cache flush can fail in a few places. If it fails in a critical
      // section, we get a DroppedSnapshotException and a replay of wal
      // is required. Currently the only way to do this is a restart of
      // the server.
      regionServer.abort("Replay of WAL required. Forcing server shutdown", ex);
      throw new ServiceException(ex);
    } catch (IOException ie) {
      throw new ServiceException(ie);
    }
  }

  @Override
  @QosPriority(priority=HConstants.ADMIN_QOS)
  public GetOnlineRegionResponse getOnlineRegion(final RpcController controller,
      final GetOnlineRegionRequest request) throws ServiceException {
    try {
      checkOpen();
      requestCount.increment();
      Map<String, Region> onlineRegions = regionServer.onlineRegions;
      List<HRegionInfo> list = new ArrayList<>(onlineRegions.size());
      for (Region region: onlineRegions.values()) {
        list.add(region.getRegionInfo());
      }
      Collections.sort(list);
      return ResponseConverter.buildGetOnlineRegionResponse(list);
    } catch (IOException ie) {
      throw new ServiceException(ie);
    }
  }

  @Override
  @QosPriority(priority=HConstants.ADMIN_QOS)
  public GetRegionInfoResponse getRegionInfo(final RpcController controller,
      final GetRegionInfoRequest request) throws ServiceException {
    try {
      checkOpen();
      requestCount.increment();
      Region region = getRegion(request.getRegion());
      HRegionInfo info = region.getRegionInfo();
      GetRegionInfoResponse.Builder builder = GetRegionInfoResponse.newBuilder();
      builder.setRegionInfo(HRegionInfo.convert(info));
      if (request.hasCompactionState() && request.getCompactionState()) {
        builder.setCompactionState(region.getCompactionState());
      }
      builder.setIsRecovering(region.isRecovering());
      return builder.build();
    } catch (IOException ie) {
      throw new ServiceException(ie);
    }
  }

  @Override
  @QosPriority(priority=HConstants.ADMIN_QOS)
  public GetRegionLoadResponse getRegionLoad(RpcController controller,
      GetRegionLoadRequest request) throws ServiceException {

    List<Region> regions;
    if (request.hasTableName()) {
      TableName tableName = ProtobufUtil.toTableName(request.getTableName());
      regions = regionServer.getOnlineRegions(tableName);
    } else {
      regions = regionServer.getOnlineRegions();
    }
    List<RegionLoad> rLoads = new ArrayList<>(regions.size());
    RegionLoad.Builder regionLoadBuilder = ClusterStatusProtos.RegionLoad.newBuilder();
    RegionSpecifier.Builder regionSpecifier = RegionSpecifier.newBuilder();

    try {
      for (Region region : regions) {
        rLoads.add(regionServer.createRegionLoad(region, regionLoadBuilder, regionSpecifier));
      }
    } catch (IOException e) {
      throw new ServiceException(e);
    }
    GetRegionLoadResponse.Builder builder = GetRegionLoadResponse.newBuilder();
    builder.addAllRegionLoads(rLoads);
    return builder.build();
  }

  @Override
  @QosPriority(priority=HConstants.ADMIN_QOS)
  public ClearCompactionQueuesResponse clearCompactionQueues(RpcController controller,
    ClearCompactionQueuesRequest request) throws ServiceException {
    LOG.debug("Client=" + RpcServer.getRequestUserName() + "/" + RpcServer.getRemoteAddress()
            + " clear compactions queue");
    ClearCompactionQueuesResponse.Builder respBuilder = ClearCompactionQueuesResponse.newBuilder();
    requestCount.increment();
    if (clearCompactionQueues.compareAndSet(false,true)) {
      try {
        checkOpen();
        regionServer.getRegionServerCoprocessorHost().preClearCompactionQueues();
        for (String queueName : request.getQueueNameList()) {
          LOG.debug("clear " + queueName + " compaction queue");
          switch (queueName) {
            case "long":
              regionServer.compactSplitThread.clearLongCompactionsQueue();
              break;
            case "short":
              regionServer.compactSplitThread.clearShortCompactionsQueue();
              break;
            default:
              LOG.warn("Unknown queue name " + queueName);
              throw new IOException("Unknown queue name " + queueName);
          }
        }
        regionServer.getRegionServerCoprocessorHost().postClearCompactionQueues();
      } catch (IOException ie) {
        throw new ServiceException(ie);
      } finally {
        clearCompactionQueues.set(false);
      }
    } else {
      LOG.warn("Clear compactions queue is executing by other admin.");
    }
    return respBuilder.build();
  }

  /**
   * Get some information of the region server.
   *
   * @param controller the RPC controller
   * @param request the request
   * @throws ServiceException
   */
  @Override
  @QosPriority(priority=HConstants.ADMIN_QOS)
  public GetServerInfoResponse getServerInfo(final RpcController controller,
      final GetServerInfoRequest request) throws ServiceException {
    try {
      checkOpen();
    } catch (IOException ie) {
      throw new ServiceException(ie);
    }
    requestCount.increment();
    int infoPort = regionServer.infoServer != null ? regionServer.infoServer.getPort() : -1;
    return ResponseConverter.buildGetServerInfoResponse(regionServer.serverName, infoPort);
  }

  @Override
  @QosPriority(priority=HConstants.ADMIN_QOS)
  public GetStoreFileResponse getStoreFile(final RpcController controller,
      final GetStoreFileRequest request) throws ServiceException {
    try {
      checkOpen();
      Region region = getRegion(request.getRegion());
      requestCount.increment();
      Set<byte[]> columnFamilies;
      if (request.getFamilyCount() == 0) {
        columnFamilies = region.getTableDesc().getFamiliesKeys();
      } else {
        columnFamilies = new TreeSet<>(Bytes.BYTES_RAWCOMPARATOR);
        for (ByteString cf: request.getFamilyList()) {
          columnFamilies.add(cf.toByteArray());
        }
      }
      int nCF = columnFamilies.size();
      List<String>  fileList = region.getStoreFileList(
        columnFamilies.toArray(new byte[nCF][]));
      GetStoreFileResponse.Builder builder = GetStoreFileResponse.newBuilder();
      builder.addAllStoreFile(fileList);
      return builder.build();
    } catch (IOException ie) {
      throw new ServiceException(ie);
    }
  }

  /**
   * Open asynchronously a region or a set of regions on the region server.
   *
   * The opening is coordinated by ZooKeeper, and this method requires the znode to be created
   *  before being called. As a consequence, this method should be called only from the master.
   * <p>
   * Different manages states for the region are:
   * </p><ul>
   *  <li>region not opened: the region opening will start asynchronously.</li>
   *  <li>a close is already in progress: this is considered as an error.</li>
   *  <li>an open is already in progress: this new open request will be ignored. This is important
   *  because the Master can do multiple requests if it crashes.</li>
   *  <li>the region is already opened:  this new open request will be ignored.</li>
   *  </ul>
   * <p>
   * Bulk assign: If there are more than 1 region to open, it will be considered as a bulk assign.
   * For a single region opening, errors are sent through a ServiceException. For bulk assign,
   * errors are put in the response as FAILED_OPENING.
   * </p>
   * @param controller the RPC controller
   * @param request the request
   * @throws ServiceException
   */
  @Override
  @QosPriority(priority=HConstants.ADMIN_QOS)
  public OpenRegionResponse openRegion(final RpcController controller,
      final OpenRegionRequest request) throws ServiceException {
    requestCount.increment();
    if (request.hasServerStartCode()) {
      // check that we are the same server that this RPC is intended for.
      long serverStartCode = request.getServerStartCode();
      if (regionServer.serverName.getStartcode() !=  serverStartCode) {
        throw new ServiceException(new DoNotRetryIOException("This RPC was intended for a " +
            "different server with startCode: " + serverStartCode + ", this server is: "
            + regionServer.serverName));
      }
    }

    OpenRegionResponse.Builder builder = OpenRegionResponse.newBuilder();
    final int regionCount = request.getOpenInfoCount();
    final Map<TableName, HTableDescriptor> htds = new HashMap<>(regionCount);
    final boolean isBulkAssign = regionCount > 1;
    try {
      checkOpen();
    } catch (IOException ie) {
      TableName tableName = null;
      if (regionCount == 1) {
        RegionInfo ri = request.getOpenInfo(0).getRegion();
        if (ri != null) {
          tableName = ProtobufUtil.toTableName(ri.getTableName());
        }
      }
      if (!TableName.META_TABLE_NAME.equals(tableName)) {
        throw new ServiceException(ie);
      }
      // We are assigning meta, wait a little for regionserver to finish initialization.
      int timeout = regionServer.conf.getInt(HConstants.HBASE_RPC_TIMEOUT_KEY,
        HConstants.DEFAULT_HBASE_RPC_TIMEOUT) >> 2; // Quarter of RPC timeout
      long endTime = System.currentTimeMillis() + timeout;
      synchronized (regionServer.online) {
        try {
          while (System.currentTimeMillis() <= endTime
              && !regionServer.isStopped() && !regionServer.isOnline()) {
            regionServer.online.wait(regionServer.msgInterval);
          }
          checkOpen();
        } catch (InterruptedException t) {
          Thread.currentThread().interrupt();
          throw new ServiceException(t);
        } catch (IOException e) {
          throw new ServiceException(e);
        }
      }
    }

    long masterSystemTime = request.hasMasterSystemTime() ? request.getMasterSystemTime() : -1;

    for (RegionOpenInfo regionOpenInfo : request.getOpenInfoList()) {
      final HRegionInfo region = HRegionInfo.convert(regionOpenInfo.getRegion());
      HTableDescriptor htd;
      try {
        String encodedName = region.getEncodedName();
        byte[] encodedNameBytes = region.getEncodedNameAsBytes();
        final Region onlineRegion = regionServer.getFromOnlineRegions(encodedName);
        if (onlineRegion != null) {
          // The region is already online. This should not happen any more.
          String error = "Received OPEN for the region:"
            + region.getRegionNameAsString() + ", which is already online";
          regionServer.abort(error);
          throw new IOException(error);
        }
        LOG.info("Open " + region.getRegionNameAsString());

        final Boolean previous = regionServer.regionsInTransitionInRS.putIfAbsent(
          encodedNameBytes, Boolean.TRUE);

        if (Boolean.FALSE.equals(previous)) {
          if (regionServer.getFromOnlineRegions(encodedName) != null) {
            // There is a close in progress. This should not happen any more.
            String error = "Received OPEN for the region:"
              + region.getRegionNameAsString() + ", which we are already trying to CLOSE";
            regionServer.abort(error);
            throw new IOException(error);
          }
          regionServer.regionsInTransitionInRS.put(encodedNameBytes, Boolean.TRUE);
        }

        if (Boolean.TRUE.equals(previous)) {
          // An open is in progress. This is supported, but let's log this.
          LOG.info("Receiving OPEN for the region:" +
            region.getRegionNameAsString() + ", which we are already trying to OPEN"
              + " - ignoring this new request for this region.");
        }

        // We are opening this region. If it moves back and forth for whatever reason, we don't
        // want to keep returning the stale moved record while we are opening/if we close again.
        regionServer.removeFromMovedRegions(region.getEncodedName());

        if (previous == null || !previous.booleanValue()) {
          // check if the region to be opened is marked in recovering state in ZK
          if (ZKSplitLog.isRegionMarkedRecoveringInZK(regionServer.getZooKeeper(),
              region.getEncodedName())) {
            // Check if current region open is for distributedLogReplay. This check is to support
            // rolling restart/upgrade where we want to Master/RS see same configuration
            if (!regionOpenInfo.hasOpenForDistributedLogReplay()
                  || regionOpenInfo.getOpenForDistributedLogReplay()) {
              regionServer.recoveringRegions.put(region.getEncodedName(), null);
            } else {
              // Remove stale recovery region from ZK when we open region not for recovering which
              // could happen when turn distributedLogReplay off from on.
              List<String> tmpRegions = new ArrayList<>();
              tmpRegions.add(region.getEncodedName());
              ZKSplitLog.deleteRecoveringRegionZNodes(regionServer.getZooKeeper(),
                tmpRegions);
            }
          }
          htd = htds.get(region.getTable());
          if (htd == null) {
            htd = regionServer.tableDescriptors.get(region.getTable());
            htds.put(region.getTable(), htd);
          }
          if (htd == null) {
            throw new IOException("Missing table descriptor for " + region.getEncodedName());
          }
          // If there is no action in progress, we can submit a specific handler.
          // Need to pass the expected version in the constructor.
          if (region.isMetaRegion()) {
            regionServer.service.submit(new OpenMetaHandler(
              regionServer, regionServer, region, htd, masterSystemTime));
          } else {
            if (regionOpenInfo.getFavoredNodesCount() > 0) {
              regionServer.updateRegionFavoredNodesMapping(region.getEncodedName(),
                  regionOpenInfo.getFavoredNodesList());
            }
            if (htd.getPriority() >= HConstants.ADMIN_QOS || region.getTable().isSystemTable()) {
              regionServer.service.submit(new OpenPriorityRegionHandler(
                regionServer, regionServer, region, htd, masterSystemTime));
            } else {
              regionServer.service.submit(new OpenRegionHandler(
                regionServer, regionServer, region, htd, masterSystemTime));
            }
          }
        }

        builder.addOpeningState(RegionOpeningState.OPENED);

      } catch (KeeperException zooKeeperEx) {
        LOG.error("Can't retrieve recovering state from zookeeper", zooKeeperEx);
        throw new ServiceException(zooKeeperEx);
      } catch (IOException ie) {
        LOG.warn("Failed opening region " + region.getRegionNameAsString(), ie);
        if (isBulkAssign) {
          builder.addOpeningState(RegionOpeningState.FAILED_OPENING);
        } else {
          throw new ServiceException(ie);
        }
      }
    }
    return builder.build();
  }

  /**
   *  Wamrmup a region on this server.
   *
   * This method should only be called by Master. It synchrnously opens the region and
   * closes the region bringing the most important pages in cache.
   * <p>
   *
   * @param controller the RPC controller
   * @param request the request
   * @throws ServiceException
   */
  @Override
  public WarmupRegionResponse warmupRegion(final RpcController controller,
      final WarmupRegionRequest request) throws ServiceException {

    RegionInfo regionInfo = request.getRegionInfo();
    final HRegionInfo region = HRegionInfo.convert(regionInfo);
    HTableDescriptor htd;
    WarmupRegionResponse response = WarmupRegionResponse.getDefaultInstance();

    try {
      checkOpen();
      String encodedName = region.getEncodedName();
      byte[] encodedNameBytes = region.getEncodedNameAsBytes();
      final Region onlineRegion = regionServer.getFromOnlineRegions(encodedName);

      if (onlineRegion != null) {
        LOG.info("Region already online. Skipping warming up " + region);
        return response;
      }

      if (LOG.isDebugEnabled()) {
        LOG.debug("Warming up Region " + region.getRegionNameAsString());
      }

      htd = regionServer.tableDescriptors.get(region.getTable());

      if (regionServer.getRegionsInTransitionInRS().containsKey(encodedNameBytes)) {
        LOG.info("Region is in transition. Skipping warmup " + region);
        return response;
      }

      HRegion.warmupHRegion(region, htd, regionServer.getWAL(region),
          regionServer.getConfiguration(), regionServer, null);

    } catch (IOException ie) {
      LOG.error("Failed warming up region " + region.getRegionNameAsString(), ie);
      throw new ServiceException(ie);
    }

    return response;
  }

  /**
   * Replay the given changes when distributedLogReplay WAL edits from a failed RS. The guarantee is
   * that the given mutations will be durable on the receiving RS if this method returns without any
   * exception.
   * @param controller the RPC controller
   * @param request the request
   * @throws ServiceException
   */
  @Override
  @QosPriority(priority = HConstants.REPLAY_QOS)
  public ReplicateWALEntryResponse replay(final RpcController controller,
      final ReplicateWALEntryRequest request) throws ServiceException {
    long before = EnvironmentEdgeManager.currentTime();
    CellScanner cells = ((HBaseRpcController) controller).cellScanner();
    try {
      checkOpen();
      List<WALEntry> entries = request.getEntryList();
      if (entries == null || entries.isEmpty()) {
        // empty input
        return ReplicateWALEntryResponse.newBuilder().build();
      }
      ByteString regionName = entries.get(0).getKey().getEncodedRegionName();
      Region region = regionServer.getRegionByEncodedName(regionName.toStringUtf8());
      RegionCoprocessorHost coprocessorHost =
          ServerRegionReplicaUtil.isDefaultReplica(region.getRegionInfo())
            ? region.getCoprocessorHost()
            : null; // do not invoke coprocessors if this is a secondary region replica
      List<Pair<WALKey, WALEdit>> walEntries = new ArrayList<>();

      // Skip adding the edits to WAL if this is a secondary region replica
      boolean isPrimary = RegionReplicaUtil.isDefaultReplica(region.getRegionInfo());
      Durability durability = isPrimary ? Durability.USE_DEFAULT : Durability.SKIP_WAL;

      for (WALEntry entry : entries) {
        if (!regionName.equals(entry.getKey().getEncodedRegionName())) {
          throw new NotServingRegionException("Replay request contains entries from multiple " +
              "regions. First region:" + regionName.toStringUtf8() + " , other region:"
              + entry.getKey().getEncodedRegionName());
        }
        if (regionServer.nonceManager != null && isPrimary) {
          long nonceGroup = entry.getKey().hasNonceGroup()
            ? entry.getKey().getNonceGroup() : HConstants.NO_NONCE;
          long nonce = entry.getKey().hasNonce() ? entry.getKey().getNonce() : HConstants.NO_NONCE;
          regionServer.nonceManager.reportOperationFromWal(
              nonceGroup,
              nonce,
              entry.getKey().getWriteTime());
        }
        Pair<WALKey, WALEdit> walEntry = (coprocessorHost == null) ? null : new Pair<>();
        List<WALSplitter.MutationReplay> edits = WALSplitter.getMutationsFromWALEntry(entry,
          cells, walEntry, durability);
        if (coprocessorHost != null) {
          // Start coprocessor replay here. The coprocessor is for each WALEdit instead of a
          // KeyValue.
          if (coprocessorHost.preWALRestore(region.getRegionInfo(), walEntry.getFirst(),
            walEntry.getSecond())) {
            // if bypass this log entry, ignore it ...
            continue;
          }
          walEntries.add(walEntry);
        }
        if(edits!=null && !edits.isEmpty()) {
          // HBASE-17924
          // sort to improve lock efficiency
          Collections.sort(edits);
          long replaySeqId = (entry.getKey().hasOrigSequenceNumber()) ?
            entry.getKey().getOrigSequenceNumber() : entry.getKey().getLogSequenceNumber();
          OperationStatus[] result = doReplayBatchOp(region, edits, replaySeqId);
          // check if it's a partial success
          for (int i = 0; result != null && i < result.length; i++) {
            if (result[i] != OperationStatus.SUCCESS) {
              throw new IOException(result[i].getExceptionMsg());
            }
          }
        }
      }

      //sync wal at the end because ASYNC_WAL is used above
      WAL wal = getWAL(region);
      if (wal != null) {
        wal.sync();
      }

      if (coprocessorHost != null) {
        for (Pair<WALKey, WALEdit> entry : walEntries) {
          coprocessorHost.postWALRestore(region.getRegionInfo(), entry.getFirst(),
            entry.getSecond());
        }
      }
      return ReplicateWALEntryResponse.newBuilder().build();
    } catch (IOException ie) {
      throw new ServiceException(ie);
    } finally {
      if (regionServer.metricsRegionServer != null) {
        regionServer.metricsRegionServer.updateReplay(
          EnvironmentEdgeManager.currentTime() - before);
      }
    }
  }

  WAL getWAL(Region region) {
    return ((HRegion)region).getWAL();
  }

  /**
   * Replicate WAL entries on the region server.
   *
   * @param controller the RPC controller
   * @param request the request
   * @throws ServiceException
   */
  @Override
  @QosPriority(priority=HConstants.REPLICATION_QOS)
  public ReplicateWALEntryResponse replicateWALEntry(final RpcController controller,
      final ReplicateWALEntryRequest request) throws ServiceException {
    try {
      checkOpen();
      if (regionServer.replicationSinkHandler != null) {
        requestCount.increment();
        List<WALEntry> entries = request.getEntryList();
        CellScanner cellScanner = ((HBaseRpcController)controller).cellScanner();
        regionServer.getRegionServerCoprocessorHost().preReplicateLogEntries(entries, cellScanner);
        regionServer.replicationSinkHandler.replicateLogEntries(entries, cellScanner,
          request.getReplicationClusterId(), request.getSourceBaseNamespaceDirPath(),
          request.getSourceHFileArchiveDirPath());
        regionServer.getRegionServerCoprocessorHost().postReplicateLogEntries(entries, cellScanner);
        return ReplicateWALEntryResponse.newBuilder().build();
      } else {
        throw new ServiceException("Replication services are not initialized yet");
      }
    } catch (IOException ie) {
      throw new ServiceException(ie);
    }
  }

  /**
   * Roll the WAL writer of the region server.
   * @param controller the RPC controller
   * @param request the request
   * @throws ServiceException
   */
  @Override
  public RollWALWriterResponse rollWALWriter(final RpcController controller,
      final RollWALWriterRequest request) throws ServiceException {
    try {
      checkOpen();
      requestCount.increment();
      regionServer.getRegionServerCoprocessorHost().preRollWALWriterRequest();
      regionServer.walRoller.requestRollAll();
      regionServer.getRegionServerCoprocessorHost().postRollWALWriterRequest();
      RollWALWriterResponse.Builder builder = RollWALWriterResponse.newBuilder();
      return builder.build();
    } catch (IOException ie) {
      throw new ServiceException(ie);
    }
  }

  /**
   * Split a region on the region server.
   *
   * @param controller the RPC controller
   * @param request the request
   * @throws ServiceException
   */
  @Override
  @QosPriority(priority=HConstants.ADMIN_QOS)
  public SplitRegionResponse splitRegion(final RpcController controller,
      final SplitRegionRequest request) throws ServiceException {
    try {
      checkOpen();
      requestCount.increment();
      Region region = getRegion(request.getRegion());
      region.startRegionOperation(Operation.SPLIT_REGION);
      if (region.getRegionInfo().getReplicaId() != HRegionInfo.DEFAULT_REPLICA_ID) {
        throw new IOException("Can't split replicas directly. "
            + "Replicas are auto-split when their primary is split.");
      }
      LOG.info("Splitting " + region.getRegionInfo().getRegionNameAsString());
      region.flush(true);
      byte[] splitPoint = null;
      if (request.hasSplitPoint()) {
        splitPoint = request.getSplitPoint().toByteArray();
      }
      ((HRegion)region).forceSplit(splitPoint);
      regionServer.compactSplitThread.requestSplit(region, ((HRegion)region).checkSplit(),
        RpcServer.getRequestUser());
      return SplitRegionResponse.newBuilder().build();
    } catch (DroppedSnapshotException ex) {
      regionServer.abort("Replay of WAL required. Forcing server shutdown", ex);
      throw new ServiceException(ex);
    } catch (IOException ie) {
      throw new ServiceException(ie);
    }
  }

  /**
   * Stop the region server.
   *
   * @param controller the RPC controller
   * @param request the request
   * @throws ServiceException
   */
  @Override
  @QosPriority(priority=HConstants.ADMIN_QOS)
  public StopServerResponse stopServer(final RpcController controller,
      final StopServerRequest request) throws ServiceException {
    requestCount.increment();
    String reason = request.getReason();
    regionServer.stop(reason);
    return StopServerResponse.newBuilder().build();
  }

  @Override
  public UpdateFavoredNodesResponse updateFavoredNodes(RpcController controller,
      UpdateFavoredNodesRequest request) throws ServiceException {
    List<UpdateFavoredNodesRequest.RegionUpdateInfo> openInfoList = request.getUpdateInfoList();
    UpdateFavoredNodesResponse.Builder respBuilder = UpdateFavoredNodesResponse.newBuilder();
    for (UpdateFavoredNodesRequest.RegionUpdateInfo regionUpdateInfo : openInfoList) {
      HRegionInfo hri = HRegionInfo.convert(regionUpdateInfo.getRegion());
      if (regionUpdateInfo.getFavoredNodesCount() > 0) {
        regionServer.updateRegionFavoredNodesMapping(hri.getEncodedName(),
          regionUpdateInfo.getFavoredNodesList());
      }
    }
    respBuilder.setResponse(openInfoList.size());
    return respBuilder.build();
  }

  /**
   * Atomically bulk load several HFiles into an open region
   * @return true if successful, false is failed but recoverably (no action)
   * @throws ServiceException if failed unrecoverably
   */
  @Override
  public BulkLoadHFileResponse bulkLoadHFile(final RpcController controller,
      final BulkLoadHFileRequest request) throws ServiceException {
    long start = EnvironmentEdgeManager.currentTime();
    try {
      checkOpen();
      requestCount.increment();
      Region region = getRegion(request.getRegion());
      boolean bypass = false;
      boolean loaded = false;
      Map<byte[], List<Path>> map = null;

      if (!request.hasBulkToken()) {
        // Old style bulk load. This will not be supported in future releases
        List<Pair<byte[], String>> familyPaths = new ArrayList<>(request.getFamilyPathCount());
        for (FamilyPath familyPath : request.getFamilyPathList()) {
          familyPaths.add(new Pair<>(familyPath.getFamily().toByteArray(), familyPath.getPath()));
        }
        if (region.getCoprocessorHost() != null) {
          bypass = region.getCoprocessorHost().preBulkLoadHFile(familyPaths);
        }
        try {
          if (!bypass) {
            map = region.bulkLoadHFiles(familyPaths, request.getAssignSeqNum(), null,
                request.getCopyFile());
            if (map != null) {
              loaded = true;
            }
          }
        } finally {
          if (region.getCoprocessorHost() != null) {
            loaded = region.getCoprocessorHost().postBulkLoadHFile(familyPaths, map, loaded);
          }
        }
      } else {
        // secure bulk load
        map = regionServer.secureBulkLoadManager.secureBulkLoadHFiles(region, request);
      }
      BulkLoadHFileResponse.Builder builder = BulkLoadHFileResponse.newBuilder();
      if (map != null) {
        loaded = true;
      }
      builder.setLoaded(loaded);
      return builder.build();
    } catch (IOException ie) {
      throw new ServiceException(ie);
    } finally {
      if (regionServer.metricsRegionServer != null) {
        regionServer.metricsRegionServer.updateBulkLoad(
            EnvironmentEdgeManager.currentTime() - start);
      }
    }
  }

  @Override
  public PrepareBulkLoadResponse prepareBulkLoad(RpcController controller,
      PrepareBulkLoadRequest request) throws ServiceException {
    try {
      checkOpen();
      requestCount.increment();

      Region region = getRegion(request.getRegion());

      String bulkToken = regionServer.secureBulkLoadManager.prepareBulkLoad(region, request);
      PrepareBulkLoadResponse.Builder builder = PrepareBulkLoadResponse.newBuilder();
      builder.setBulkToken(bulkToken);
      return builder.build();
    } catch (IOException ie) {
      throw new ServiceException(ie);
    }
  }

  @Override
  public CleanupBulkLoadResponse cleanupBulkLoad(RpcController controller,
      CleanupBulkLoadRequest request) throws ServiceException {
    try {
      checkOpen();
      requestCount.increment();

      Region region = getRegion(request.getRegion());

      regionServer.secureBulkLoadManager.cleanupBulkLoad(region, request);
      CleanupBulkLoadResponse response = CleanupBulkLoadResponse.newBuilder().build();
      return response;
    } catch (IOException ie) {
      throw new ServiceException(ie);
    }
  }

  @Override
  public CoprocessorServiceResponse execService(final RpcController controller,
      final CoprocessorServiceRequest request) throws ServiceException {
    try {
      checkOpen();
      requestCount.increment();
      Region region = getRegion(request.getRegion());
      com.google.protobuf.Message result = execServiceOnRegion(region, request.getCall());
      CoprocessorServiceResponse.Builder builder = CoprocessorServiceResponse.newBuilder();
      builder.setRegion(RequestConverter.buildRegionSpecifier(
        RegionSpecifierType.REGION_NAME, region.getRegionInfo().getRegionName()));
      // TODO: COPIES!!!!!!
      builder.setValue(builder.getValueBuilder().setName(result.getClass().getName()).
        setValue(org.apache.hadoop.hbase.shaded.com.google.protobuf.ByteString.
            copyFrom(result.toByteArray())));
      return builder.build();
    } catch (IOException ie) {
      throw new ServiceException(ie);
    }
  }

  private com.google.protobuf.Message execServiceOnRegion(Region region,
      final ClientProtos.CoprocessorServiceCall serviceCall) throws IOException {
    // ignore the passed in controller (from the serialized call)
    ServerRpcController execController = new ServerRpcController();
    return region.execService(execController, serviceCall);
  }

  /**
   * Get data from a table.
   *
   * @param controller the RPC controller
   * @param request the get request
   * @throws ServiceException
   */
  @Override
  public GetResponse get(final RpcController controller,
      final GetRequest request) throws ServiceException {
    long before = EnvironmentEdgeManager.currentTime();
    OperationQuota quota = null;
    try {
      checkOpen();
      requestCount.increment();
      rpcGetRequestCount.increment();
      Region region = getRegion(request.getRegion());

      GetResponse.Builder builder = GetResponse.newBuilder();
      ClientProtos.Get get = request.getGet();
      Boolean existence = null;
      Result r = null;
      RpcCallContext context = RpcServer.getCurrentCall();
      quota = getQuotaManager().checkQuota(region, OperationQuota.OperationType.GET);

      Get clientGet = ProtobufUtil.toGet(get);
      if (get.getExistenceOnly() && region.getCoprocessorHost() != null) {
        existence = region.getCoprocessorHost().preExists(clientGet);
      }
      if (existence == null) {
        if (context != null) {
          r = get(clientGet, ((HRegion) region), null, context);
        } else {
          // for test purpose
          r = region.get(clientGet);
        }
        if (get.getExistenceOnly()) {
          boolean exists = r.getExists();
          if (region.getCoprocessorHost() != null) {
            exists = region.getCoprocessorHost().postExists(clientGet, exists);
          }
          existence = exists;
        }
      }
      if (existence != null) {
        ClientProtos.Result pbr =
            ProtobufUtil.toResult(existence, region.getRegionInfo().getReplicaId() != 0);
        builder.setResult(pbr);
      } else if (r != null) {
        ClientProtos.Result pbr;
        if (isClientCellBlockSupport(context) && controller instanceof HBaseRpcController
            && VersionInfoUtil.hasMinimumVersion(context.getClientVersionInfo(), 1, 3)) {
          pbr = ProtobufUtil.toResultNoData(r);
          ((HBaseRpcController) controller).setCellScanner(CellUtil.createCellScanner(r
              .rawCells()));
          addSize(context, r, null);
        } else {
          pbr = ProtobufUtil.toResult(r);
        }
        builder.setResult(pbr);
      }
      if (r != null) {
        quota.addGetResult(r);
      }
      return builder.build();
    } catch (IOException ie) {
      throw new ServiceException(ie);
    } finally {
      if (regionServer.metricsRegionServer != null) {
        regionServer.metricsRegionServer.updateGet(EnvironmentEdgeManager.currentTime() - before);
      }
      if (quota != null) {
        quota.close();
      }
    }
  }

  private Result get(Get get, HRegion region, RegionScannersCloseCallBack closeCallBack,
      RpcCallContext context) throws IOException {
    region.prepareGet(get);
    List<Cell> results = new ArrayList<>();
    boolean stale = region.getRegionInfo().getReplicaId() != 0;
    // pre-get CP hook
    if (region.getCoprocessorHost() != null) {
      if (region.getCoprocessorHost().preGet(get, results)) {
        return Result
            .create(results, get.isCheckExistenceOnly() ? !results.isEmpty() : null, stale);
      }
    }
    long before = EnvironmentEdgeManager.currentTime();
    Scan scan = new Scan(get);
    if (scan.getLoadColumnFamiliesOnDemandValue() == null) {
      scan.setLoadColumnFamiliesOnDemand(region.isLoadingCfsOnDemandDefault());
    }
    RegionScanner scanner = null;
    try {
      scanner = region.getScanner(scan);
      scanner.next(results);
    } finally {
      if (scanner != null) {
        if (closeCallBack == null) {
          // If there is a context then the scanner can be added to the current
          // RpcCallContext. The rpc callback will take care of closing the
          // scanner, for eg in case
          // of get()
          assert scanner instanceof org.apache.hadoop.hbase.ipc.RpcCallback;
          context.setCallBack((RegionScannerImpl) scanner);
        } else {
          // The call is from multi() where the results from the get() are
          // aggregated and then send out to the
          // rpc. The rpccall back will close all such scanners created as part
          // of multi().
          closeCallBack.addScanner(scanner);
        }
      }
    }

    // post-get CP hook
    if (region.getCoprocessorHost() != null) {
      region.getCoprocessorHost().postGet(get, results);
    }
    region.metricsUpdateForGet(results, before);
    return Result.create(results, get.isCheckExistenceOnly() ? !results.isEmpty() : null, stale);
  }

  /**
   * Execute multiple actions on a table: get, mutate, and/or execCoprocessor
   *
   * @param rpcc the RPC controller
   * @param request the multi request
   * @throws ServiceException
   */
  @Override
  public MultiResponse multi(final RpcController rpcc, final MultiRequest request)
  throws ServiceException {
    try {
      checkOpen();
    } catch (IOException ie) {
      throw new ServiceException(ie);
    }

    // rpc controller is how we bring in data via the back door;  it is unprotobuf'ed data.
    // It is also the conduit via which we pass back data.
    HBaseRpcController controller = (HBaseRpcController)rpcc;
    CellScanner cellScanner = controller != null ? controller.cellScanner(): null;
    if (controller != null) {
      controller.setCellScanner(null);
    }

    long nonceGroup = request.hasNonceGroup() ? request.getNonceGroup() : HConstants.NO_NONCE;

    // this will contain all the cells that we need to return. It's created later, if needed.
    List<CellScannable> cellsToReturn = null;
    MultiResponse.Builder responseBuilder = MultiResponse.newBuilder();
    RegionActionResult.Builder regionActionResultBuilder = RegionActionResult.newBuilder();
    Boolean processed = null;
    RegionScannersCloseCallBack closeCallBack = null;
    RpcCallContext context = RpcServer.getCurrentCall();
    this.rpcMultiRequestCount.increment();
    Map<RegionSpecifier, ClientProtos.RegionLoadStats> regionStats = new HashMap<>(request
      .getRegionActionCount());
    for (RegionAction regionAction : request.getRegionActionList()) {
      this.requestCount.add(regionAction.getActionCount());
      OperationQuota quota;
      Region region;
      regionActionResultBuilder.clear();
      RegionSpecifier regionSpecifier = regionAction.getRegion();
      try {
        region = getRegion(regionSpecifier);
        quota = getQuotaManager().checkQuota(region, regionAction.getActionList());
      } catch (IOException e) {
        rpcServer.getMetrics().exception(e);
        regionActionResultBuilder.setException(ResponseConverter.buildException(e));
        responseBuilder.addRegionActionResult(regionActionResultBuilder.build());
        // All Mutations in this RegionAction not executed as we can not see the Region online here
        // in this RS. Will be retried from Client. Skipping all the Cells in CellScanner
        // corresponding to these Mutations.
        if (cellScanner != null) {
          skipCellsForMutations(regionAction.getActionList(), cellScanner);
        }
        continue;  // For this region it's a failure.
      }

      if (regionAction.hasAtomic() && regionAction.getAtomic()) {
        // How does this call happen?  It may need some work to play well w/ the surroundings.
        // Need to return an item per Action along w/ Action index.  TODO.
        try {
          if (request.hasCondition()) {
            Condition condition = request.getCondition();
            byte[] row = condition.getRow().toByteArray();
            byte[] family = condition.getFamily().toByteArray();
            byte[] qualifier = condition.getQualifier().toByteArray();
            CompareOp compareOp = CompareOp.valueOf(condition.getCompareType().name());
            ByteArrayComparable comparator =
                ProtobufUtil.toComparator(condition.getComparator());
            processed = checkAndRowMutate(region, regionAction.getActionList(),
                  cellScanner, row, family, qualifier, compareOp,
                  comparator, regionActionResultBuilder);
          } else {
            mutateRows(region, regionAction.getActionList(), cellScanner,
                regionActionResultBuilder);
            processed = Boolean.TRUE;
          }
        } catch (IOException e) {
          rpcServer.getMetrics().exception(e);
          // As it's atomic, we may expect it's a global failure.
          regionActionResultBuilder.setException(ResponseConverter.buildException(e));
        }
      } else {
        // doNonAtomicRegionMutation manages the exception internally
        if (context != null && closeCallBack == null) {
          // An RpcCallBack that creates a list of scanners that needs to perform callBack
          // operation on completion of multiGets.
          // Set this only once
          closeCallBack = new RegionScannersCloseCallBack();
          context.setCallBack(closeCallBack);
        }
        cellsToReturn = doNonAtomicRegionMutation(region, quota, regionAction, cellScanner,
            regionActionResultBuilder, cellsToReturn, nonceGroup, closeCallBack, context);
      }
      responseBuilder.addRegionActionResult(regionActionResultBuilder.build());
      quota.close();
      ClientProtos.RegionLoadStats regionLoadStats = ((HRegion)region).getLoadStatistics();
      if(regionLoadStats != null) {
        regionStats.put(regionSpecifier, regionLoadStats);
      }
    }
    // Load the controller with the Cells to return.
    if (cellsToReturn != null && !cellsToReturn.isEmpty() && controller != null) {
      controller.setCellScanner(CellUtil.createCellScanner(cellsToReturn));
    }

    if (processed != null) {
      responseBuilder.setProcessed(processed);
    }

    MultiRegionLoadStats.Builder builder = MultiRegionLoadStats.newBuilder();
    for(Entry<RegionSpecifier, ClientProtos.RegionLoadStats> stat: regionStats.entrySet()){
      builder.addRegion(stat.getKey());
      builder.addStat(stat.getValue());
    }
    responseBuilder.setRegionStatistics(builder);
    return responseBuilder.build();
  }

  private void skipCellsForMutations(List<Action> actions, CellScanner cellScanner) {
    for (Action action : actions) {
      skipCellsForMutation(action, cellScanner);
    }
  }

  private void skipCellsForMutation(Action action, CellScanner cellScanner) {
    try {
      if (action.hasMutation()) {
        MutationProto m = action.getMutation();
        if (m.hasAssociatedCellCount()) {
          for (int i = 0; i < m.getAssociatedCellCount(); i++) {
            cellScanner.advance();
          }
        }
      }
    } catch (IOException e) {
      // No need to handle these Individual Muatation level issue. Any way this entire RegionAction
      // marked as failed as we could not see the Region here. At client side the top level
      // RegionAction exception will be considered first.
      LOG.error("Error while skipping Cells in CellScanner for invalid Region Mutations", e);
    }
  }

  /**
   * Mutate data in a table.
   *
   * @param rpcc the RPC controller
   * @param request the mutate request
   * @throws ServiceException
   */
  @Override
  public MutateResponse mutate(final RpcController rpcc,
      final MutateRequest request) throws ServiceException {
    // rpc controller is how we bring in data via the back door;  it is unprotobuf'ed data.
    // It is also the conduit via which we pass back data.
    HBaseRpcController controller = (HBaseRpcController)rpcc;
    CellScanner cellScanner = controller != null ? controller.cellScanner() : null;
    OperationQuota quota = null;
    RpcCallContext context = RpcServer.getCurrentCall();
    // Clear scanner so we are not holding on to reference across call.
    if (controller != null) {
      controller.setCellScanner(null);
    }
    try {
      checkOpen();
      requestCount.increment();
      rpcMutateRequestCount.increment();
      Region region = getRegion(request.getRegion());
      MutateResponse.Builder builder = MutateResponse.newBuilder();
      MutationProto mutation = request.getMutation();
      if (!region.getRegionInfo().isMetaTable()) {
        regionServer.cacheFlusher.reclaimMemStoreMemory();
      }
      long nonceGroup = request.hasNonceGroup() ? request.getNonceGroup() : HConstants.NO_NONCE;
      Result r = null;
      Boolean processed = null;
      MutationType type = mutation.getMutateType();

      quota = getQuotaManager().checkQuota(region, OperationQuota.OperationType.MUTATE);

      switch (type) {
      case APPEND:
        // TODO: this doesn't actually check anything.
        r = append(region, quota, mutation, cellScanner, nonceGroup);
        break;
      case INCREMENT:
        // TODO: this doesn't actually check anything.
        r = increment(region, quota, mutation, cellScanner, nonceGroup);
        break;
      case PUT:
        Put put = ProtobufUtil.toPut(mutation, cellScanner);
        checkCellSizeLimit(region, put);
        quota.addMutation(put);
        if (request.hasCondition()) {
          Condition condition = request.getCondition();
          byte[] row = condition.getRow().toByteArray();
          byte[] family = condition.getFamily().toByteArray();
          byte[] qualifier = condition.getQualifier().toByteArray();
          CompareOp compareOp = CompareOp.valueOf(condition.getCompareType().name());
          ByteArrayComparable comparator =
            ProtobufUtil.toComparator(condition.getComparator());
          if (region.getCoprocessorHost() != null) {
            processed = region.getCoprocessorHost().preCheckAndPut(
              row, family, qualifier, compareOp, comparator, put);
          }
          if (processed == null) {
            boolean result = region.checkAndMutate(row, family,
              qualifier, compareOp, comparator, put, true);
            if (region.getCoprocessorHost() != null) {
              result = region.getCoprocessorHost().postCheckAndPut(row, family,
                qualifier, compareOp, comparator, put, result);
            }
            processed = result;
          }
        } else {
          region.put(put);
          processed = Boolean.TRUE;
        }
        break;
      case DELETE:
        Delete delete = ProtobufUtil.toDelete(mutation, cellScanner);
        checkCellSizeLimit(region, delete);
        quota.addMutation(delete);
        if (request.hasCondition()) {
          Condition condition = request.getCondition();
          byte[] row = condition.getRow().toByteArray();
          byte[] family = condition.getFamily().toByteArray();
          byte[] qualifier = condition.getQualifier().toByteArray();
          CompareOp compareOp = CompareOp.valueOf(condition.getCompareType().name());
          ByteArrayComparable comparator =
            ProtobufUtil.toComparator(condition.getComparator());
          if (region.getCoprocessorHost() != null) {
            processed = region.getCoprocessorHost().preCheckAndDelete(
              row, family, qualifier, compareOp, comparator, delete);
          }
          if (processed == null) {
            boolean result = region.checkAndMutate(row, family,
              qualifier, compareOp, comparator, delete, true);
            if (region.getCoprocessorHost() != null) {
              result = region.getCoprocessorHost().postCheckAndDelete(row, family,
                qualifier, compareOp, comparator, delete, result);
            }
            processed = result;
          }
        } else {
          region.delete(delete);
          processed = Boolean.TRUE;
        }
        break;
      default:
          throw new DoNotRetryIOException(
            "Unsupported mutate type: " + type.name());
      }
      if (processed != null) {
        builder.setProcessed(processed.booleanValue());
      }
      boolean clientCellBlockSupported = isClientCellBlockSupport(context);
      addResult(builder, r, controller, clientCellBlockSupported);
      if (clientCellBlockSupported) {
        addSize(context, r, null);
      }
      return builder.build();
    } catch (IOException ie) {
      regionServer.checkFileSystem();
      throw new ServiceException(ie);
    } finally {
      if (quota != null) {
        quota.close();
      }
    }
  }

  // This is used to keep compatible with the old client implementation. Consider remove it if we
  // decide to drop the support of the client that still sends close request to a region scanner
  // which has already been exhausted.
  @Deprecated
  private static final IOException SCANNER_ALREADY_CLOSED = new IOException() {

    private static final long serialVersionUID = -4305297078988180130L;

    @Override
    public Throwable fillInStackTrace() {
      return this;
    }
  };

  private RegionScannerHolder getRegionScanner(ScanRequest request) throws IOException {
    String scannerName = Long.toString(request.getScannerId());
    RegionScannerHolder rsh = scanners.get(scannerName);
    if (rsh == null) {
      // just ignore the close request if scanner does not exists.
      if (request.hasCloseScanner() && request.getCloseScanner()) {
        throw SCANNER_ALREADY_CLOSED;
      } else {
        LOG.warn("Client tried to access missing scanner " + scannerName);
        throw new UnknownScannerException(
            "Unknown scanner '" + scannerName + "'. This can happen due to any of the following "
                + "reasons: a) Scanner id given is wrong, b) Scanner lease expired because of "
                + "long wait between consecutive client checkins, c) Server may be closing down, "
                + "d) RegionServer restart during upgrade.\nIf the issue is due to reason (b), a "
                + "possible fix would be increasing the value of"
                + "'hbase.client.scanner.timeout.period' configuration.");
      }
    }
    HRegionInfo hri = rsh.s.getRegionInfo();
    // Yes, should be the same instance
    if (regionServer.getOnlineRegion(hri.getRegionName()) != rsh.r) {
      String msg = "Region was re-opened after the scanner" + scannerName + " was created: "
          + hri.getRegionNameAsString();
      LOG.warn(msg + ", closing...");
      scanners.remove(scannerName);
      try {
        rsh.s.close();
      } catch (IOException e) {
        LOG.warn("Getting exception closing " + scannerName, e);
      } finally {
        try {
          regionServer.leases.cancelLease(scannerName);
        } catch (LeaseException e) {
          LOG.warn("Getting exception closing " + scannerName, e);
        }
      }
      throw new NotServingRegionException(msg);
    }
    return rsh;
  }

  private RegionScannerHolder newRegionScanner(ScanRequest request, ScanResponse.Builder builder)
      throws IOException {
    Region region = getRegion(request.getRegion());
    ClientProtos.Scan protoScan = request.getScan();
    boolean isLoadingCfsOnDemandSet = protoScan.hasLoadColumnFamiliesOnDemand();
    Scan scan = ProtobufUtil.toScan(protoScan);
    // if the request doesn't set this, get the default region setting.
    if (!isLoadingCfsOnDemandSet) {
      scan.setLoadColumnFamiliesOnDemand(region.isLoadingCfsOnDemandDefault());
    }

    if (!scan.hasFamilies()) {
      // Adding all families to scanner
      for (byte[] family : region.getTableDesc().getFamiliesKeys()) {
        scan.addFamily(family);
      }
    }
    RegionScanner scanner = null;
    if (region.getCoprocessorHost() != null) {
      scanner = region.getCoprocessorHost().preScannerOpen(scan);
    }
    if (scanner == null) {
      scanner = region.getScanner(scan);
    }
    if (region.getCoprocessorHost() != null) {
      scanner = region.getCoprocessorHost().postScannerOpen(scan, scanner);
    }
    long scannerId = this.scannerIdGen.incrementAndGet();
    builder.setScannerId(scannerId);
    builder.setMvccReadPoint(scanner.getMvccReadPoint());
    builder.setTtl(scannerLeaseTimeoutPeriod);
    String scannerName = String.valueOf(scannerId);
    return addScanner(scannerName, scanner, region);
  }

  private void checkScanNextCallSeq(ScanRequest request, RegionScannerHolder rsh)
      throws OutOfOrderScannerNextException {
    // if nextCallSeq does not match throw Exception straight away. This needs to be
    // performed even before checking of Lease.
    // See HBASE-5974
    if (request.hasNextCallSeq()) {
      long callSeq = request.getNextCallSeq();
      if (!rsh.incNextCallSeq(callSeq)) {
        throw new OutOfOrderScannerNextException("Expected nextCallSeq: " + rsh.getNextCallSeq()
            + " But the nextCallSeq got from client: " + request.getNextCallSeq() + "; request="
            + TextFormat.shortDebugString(request));
      }
    }
  }

  private void addScannerLeaseBack(Leases.Lease lease) {
    try {
      regionServer.leases.addLease(lease);
    } catch (LeaseStillHeldException e) {
      // should not happen as the scanner id is unique.
      throw new AssertionError(e);
    }
  }

  private long getTimeLimit(HBaseRpcController controller, boolean allowHeartbeatMessages) {
    // Set the time limit to be half of the more restrictive timeout value (one of the
    // timeout values must be positive). In the event that both values are positive, the
    // more restrictive of the two is used to calculate the limit.
    if (allowHeartbeatMessages && (scannerLeaseTimeoutPeriod > 0 || rpcTimeout > 0)) {
      long timeLimitDelta;
      if (scannerLeaseTimeoutPeriod > 0 && rpcTimeout > 0) {
        timeLimitDelta = Math.min(scannerLeaseTimeoutPeriod, rpcTimeout);
      } else {
        timeLimitDelta = scannerLeaseTimeoutPeriod > 0 ? scannerLeaseTimeoutPeriod : rpcTimeout;
      }
      if (controller != null && controller.getCallTimeout() > 0) {
        timeLimitDelta = Math.min(timeLimitDelta, controller.getCallTimeout());
      }
      // Use half of whichever timeout value was more restrictive... But don't allow
      // the time limit to be less than the allowable minimum (could cause an
      // immediatate timeout before scanning any data).
      timeLimitDelta = Math.max(timeLimitDelta / 2, minimumScanTimeLimitDelta);
      // XXX: Can not use EnvironmentEdge here because TestIncrementTimeRange use a
      // ManualEnvironmentEdge. Consider using System.nanoTime instead.
      return System.currentTimeMillis() + timeLimitDelta;
    }
    // Default value of timeLimit is negative to indicate no timeLimit should be
    // enforced.
    return -1L;
  }

  private void checkLimitOfRows(int numOfCompleteRows, int limitOfRows, boolean moreRows,
      ScannerContext scannerContext, ScanResponse.Builder builder) {
    if (numOfCompleteRows >= limitOfRows) {
      if (LOG.isTraceEnabled()) {
        LOG.trace("Done scanning, limit of rows reached, moreRows: " + moreRows +
            " scannerContext: " + scannerContext);
      }
      builder.setMoreResults(false);
    }
  }

  // return whether we have more results in region.
  private void scan(HBaseRpcController controller, ScanRequest request, RegionScannerHolder rsh,
      long maxQuotaResultSize, int maxResults, int limitOfRows, List<Result> results,
      ScanResponse.Builder builder, MutableObject lastBlock, RpcCallContext context)
      throws IOException {
    Region region = rsh.r;
    RegionScanner scanner = rsh.s;
    long maxResultSize;
    if (scanner.getMaxResultSize() > 0) {
      maxResultSize = Math.min(scanner.getMaxResultSize(), maxQuotaResultSize);
    } else {
      maxResultSize = maxQuotaResultSize;
    }
    // This is cells inside a row. Default size is 10 so if many versions or many cfs,
    // then we'll resize. Resizings show in profiler. Set it higher than 10. For now
    // arbitrary 32. TODO: keep record of general size of results being returned.
    List<Cell> values = new ArrayList<>(32);
    region.startRegionOperation(Operation.SCAN);
    try {
      int numOfResults = 0;
      int numOfCompleteRows = 0;
      long before = EnvironmentEdgeManager.currentTime();
      synchronized (scanner) {
        boolean stale = (region.getRegionInfo().getReplicaId() != 0);
        boolean clientHandlesPartials =
            request.hasClientHandlesPartials() && request.getClientHandlesPartials();
        boolean clientHandlesHeartbeats =
            request.hasClientHandlesHeartbeats() && request.getClientHandlesHeartbeats();

        // On the server side we must ensure that the correct ordering of partial results is
        // returned to the client to allow them to properly reconstruct the partial results.
        // If the coprocessor host is adding to the result list, we cannot guarantee the
        // correct ordering of partial results and so we prevent partial results from being
        // formed.
        boolean serverGuaranteesOrderOfPartials = results.isEmpty();
        boolean allowPartialResults = clientHandlesPartials && serverGuaranteesOrderOfPartials;
        boolean moreRows = false;

        // Heartbeat messages occur when the processing of the ScanRequest is exceeds a
        // certain time threshold on the server. When the time threshold is exceeded, the
        // server stops the scan and sends back whatever Results it has accumulated within
        // that time period (may be empty). Since heartbeat messages have the potential to
        // create partial Results (in the event that the timeout occurs in the middle of a
        // row), we must only generate heartbeat messages when the client can handle both
        // heartbeats AND partials
        boolean allowHeartbeatMessages = clientHandlesHeartbeats && allowPartialResults;

        long timeLimit = getTimeLimit(controller, allowHeartbeatMessages);

        final LimitScope sizeScope =
            allowPartialResults ? LimitScope.BETWEEN_CELLS : LimitScope.BETWEEN_ROWS;
        final LimitScope timeScope =
            allowHeartbeatMessages ? LimitScope.BETWEEN_CELLS : LimitScope.BETWEEN_ROWS;

        boolean trackMetrics = request.hasTrackScanMetrics() && request.getTrackScanMetrics();

        // Configure with limits for this RPC. Set keep progress true since size progress
        // towards size limit should be kept between calls to nextRaw
        ScannerContext.Builder contextBuilder = ScannerContext.newBuilder(true);
        // maxResultSize - either we can reach this much size for all cells(being read) data or sum
        // of heap size occupied by cells(being read). Cell data means its key and value parts.
        contextBuilder.setSizeLimit(sizeScope, maxResultSize, maxResultSize);
        contextBuilder.setBatchLimit(scanner.getBatch());
        contextBuilder.setTimeLimit(timeScope, timeLimit);
        contextBuilder.setTrackMetrics(trackMetrics);
        ScannerContext scannerContext = contextBuilder.build();
        boolean limitReached = false;
        while (numOfResults < maxResults) {
          // Reset the batch progress to 0 before every call to RegionScanner#nextRaw. The
          // batch limit is a limit on the number of cells per Result. Thus, if progress is
          // being tracked (i.e. scannerContext.keepProgress() is true) then we need to
          // reset the batch progress between nextRaw invocations since we don't want the
          // batch progress from previous calls to affect future calls
          scannerContext.setBatchProgress(0);

          // Collect values to be returned here
          moreRows = scanner.nextRaw(values, scannerContext);

          if (!values.isEmpty()) {
            if (limitOfRows > 0) {
              // First we need to check if the last result is partial and we have a row change. If
              // so then we need to increase the numOfCompleteRows.
              if (results.isEmpty()) {
                if (rsh.rowOfLastPartialResult != null &&
                    !CellUtil.matchingRow(values.get(0), rsh.rowOfLastPartialResult)) {
                  numOfCompleteRows++;
                  checkLimitOfRows(numOfCompleteRows, limitOfRows, moreRows, scannerContext,
                    builder);
                }
              } else {
                Result lastResult = results.get(results.size() - 1);
                if (lastResult.mayHaveMoreCellsInRow() &&
                    !CellUtil.matchingRow(values.get(0), lastResult.getRow())) {
                  numOfCompleteRows++;
                  checkLimitOfRows(numOfCompleteRows, limitOfRows, moreRows, scannerContext,
                    builder);
                }
              }
              if (builder.hasMoreResults() && !builder.getMoreResults()) {
                break;
              }
            }
            boolean mayHaveMoreCellsInRow = scannerContext.mayHaveMoreCellsInRow();
            Result r = Result.create(values, null, stale, mayHaveMoreCellsInRow);
            lastBlock.setValue(addSize(context, r, lastBlock.getValue()));
            results.add(r);
            numOfResults++;
            if (!mayHaveMoreCellsInRow && limitOfRows > 0) {
              numOfCompleteRows++;
              checkLimitOfRows(numOfCompleteRows, limitOfRows, moreRows, scannerContext, builder);
              if (builder.hasMoreResults() && !builder.getMoreResults()) {
                break;
              }
            }
          }
          boolean sizeLimitReached = scannerContext.checkSizeLimit(LimitScope.BETWEEN_ROWS);
          boolean timeLimitReached = scannerContext.checkTimeLimit(LimitScope.BETWEEN_ROWS);
          boolean resultsLimitReached = numOfResults >= maxResults;
          limitReached = sizeLimitReached || timeLimitReached || resultsLimitReached;

          if (limitReached || !moreRows) {
            if (LOG.isTraceEnabled()) {
              LOG.trace("Done scanning. limitReached: " + limitReached + " moreRows: " + moreRows
                  + " scannerContext: " + scannerContext);
            }
            // We only want to mark a ScanResponse as a heartbeat message in the event that
            // there are more values to be read server side. If there aren't more values,
            // marking it as a heartbeat is wasteful because the client will need to issue
            // another ScanRequest only to realize that they already have all the values
            if (moreRows) {
              // Heartbeat messages occur when the time limit has been reached.
              builder.setHeartbeatMessage(timeLimitReached);
            }
            break;
          }
          values.clear();
        }
        if (limitReached || moreRows) {
          // We stopped prematurely
          builder.setMoreResultsInRegion(true);
        } else {
          // We didn't get a single batch
          builder.setMoreResultsInRegion(false);
        }
        // Check to see if the client requested that we track metrics server side. If the
        // client requested metrics, retrieve the metrics from the scanner context.
        if (trackMetrics) {
          Map<String, Long> metrics = scannerContext.getMetrics().getMetricsMap();
          ScanMetrics.Builder metricBuilder = ScanMetrics.newBuilder();
          NameInt64Pair.Builder pairBuilder = NameInt64Pair.newBuilder();

          for (Entry<String, Long> entry : metrics.entrySet()) {
            pairBuilder.setName(entry.getKey());
            pairBuilder.setValue(entry.getValue());
            metricBuilder.addMetrics(pairBuilder.build());
          }

          builder.setScanMetrics(metricBuilder.build());
        }
      }
      region.updateReadRequestsCount(numOfResults);
      long end = EnvironmentEdgeManager.currentTime();
      long responseCellSize = context != null ? context.getResponseCellSize() : 0;
      region.getMetrics().updateScanTime(end - before);
      if (regionServer.metricsRegionServer != null) {
        regionServer.metricsRegionServer.updateScanSize(responseCellSize);
        regionServer.metricsRegionServer.updateScanTime(end - before);
      }
    } finally {
      region.closeRegionOperation();
    }
    // coprocessor postNext hook
    if (region.getCoprocessorHost() != null) {
      region.getCoprocessorHost().postScannerNext(scanner, results, maxResults, true);
    }
  }

  /**
   * Scan data in a table.
   *
   * @param controller the RPC controller
   * @param request the scan request
   * @throws ServiceException
   */
  @Override
  public ScanResponse scan(final RpcController controller, final ScanRequest request)
      throws ServiceException {
    if (controller != null && !(controller instanceof HBaseRpcController)) {
      throw new UnsupportedOperationException(
          "We only do " + "HBaseRpcControllers! FIX IF A PROBLEM: " + controller);
    }
    if (!request.hasScannerId() && !request.hasScan()) {
      throw new ServiceException(
          new DoNotRetryIOException("Missing required input: scannerId or scan"));
    }
    try {
      checkOpen();
    } catch (IOException e) {
      if (request.hasScannerId()) {
        String scannerName = Long.toString(request.getScannerId());
        if (LOG.isDebugEnabled()) {
          LOG.debug(
            "Server shutting down and client tried to access missing scanner " + scannerName);
        }
        if (regionServer.leases != null) {
          try {
            regionServer.leases.cancelLease(scannerName);
          } catch (LeaseException le) {
            // No problem, ignore
            if (LOG.isTraceEnabled()) {
              LOG.trace("Un-able to cancel lease of scanner. It could already be closed.");
            }
          }
        }
      }
      throw new ServiceException(e);
    }
    requestCount.increment();
    rpcScanRequestCount.increment();
    RegionScannerHolder rsh;
    ScanResponse.Builder builder = ScanResponse.newBuilder();
    try {
      if (request.hasScannerId()) {
        // The downstream projects such as AsyncHBase in OpenTSDB need this value. See HBASE-18000
        // for more details.
        builder.setScannerId(request.getScannerId());
        rsh = getRegionScanner(request);
      } else {
        rsh = newRegionScanner(request, builder);
      }
    } catch (IOException e) {
      if (e == SCANNER_ALREADY_CLOSED) {
        // Now we will close scanner automatically if there are no more results for this region but
        // the old client will still send a close request to us. Just ignore it and return.
        return builder.build();
      }
      throw new ServiceException(e);
    }
    Region region = rsh.r;
    String scannerName = rsh.scannerName;
    Leases.Lease lease;
    try {
      // Remove lease while its being processed in server; protects against case
      // where processing of request takes > lease expiration time.
      lease = regionServer.leases.removeLease(scannerName);
    } catch (LeaseException e) {
      throw new ServiceException(e);
    }
    if (request.hasRenew() && request.getRenew()) {
      // add back and return
      addScannerLeaseBack(lease);
      try {
        checkScanNextCallSeq(request, rsh);
      } catch (OutOfOrderScannerNextException e) {
        throw new ServiceException(e);
      }
      return builder.build();
    }
    OperationQuota quota;
    try {
      quota = getQuotaManager().checkQuota(region, OperationQuota.OperationType.SCAN);
    } catch (IOException e) {
      addScannerLeaseBack(lease);
      throw new ServiceException(e);
    };
    try {
      checkScanNextCallSeq(request, rsh);
    } catch (OutOfOrderScannerNextException e) {
      addScannerLeaseBack(lease);
      throw new ServiceException(e);
    }
    // Now we have increased the next call sequence. If we give client an error, the retry will
    // never success. So we'd better close the scanner and return a DoNotRetryIOException to client
    // and then client will try to open a new scanner.
    boolean closeScanner = request.hasCloseScanner() ? request.getCloseScanner() : false;
    int rows; // this is scan.getCaching
    if (request.hasNumberOfRows()) {
      rows = request.getNumberOfRows();
    } else {
      rows = closeScanner ? 0 : 1;
    }
    RpcCallContext context = RpcServer.getCurrentCall();
    // now let's do the real scan.
    long maxQuotaResultSize = Math.min(maxScannerResultSize, quota.getReadAvailable());
    RegionScanner scanner = rsh.s;
    // this is the limit of rows for this scan, if we the number of rows reach this value, we will
    // close the scanner.
    int limitOfRows;
    if (request.hasLimitOfRows()) {
      limitOfRows = request.getLimitOfRows();
    } else {
      limitOfRows = -1;
    }
    MutableObject lastBlock = new MutableObject();
    boolean scannerClosed = false;
    try {
      List<Result> results = new ArrayList<>();
      if (rows > 0) {
        boolean done = false;
        // Call coprocessor. Get region info from scanner.
        if (region.getCoprocessorHost() != null) {
          Boolean bypass = region.getCoprocessorHost().preScannerNext(scanner, results, rows);
          if (!results.isEmpty()) {
            for (Result r : results) {
              lastBlock.setValue(addSize(context, r, lastBlock.getValue()));
            }
          }
          if (bypass != null && bypass.booleanValue()) {
            done = true;
          }
        }
        if (!done) {
          scan((HBaseRpcController) controller, request, rsh, maxQuotaResultSize, rows, limitOfRows,
            results, builder, lastBlock, context);
        }
      }

      quota.addScanResult(results);
      addResults(builder, results, (HBaseRpcController) controller,
        RegionReplicaUtil.isDefaultReplica(region.getRegionInfo()),
        isClientCellBlockSupport(context));
      if (scanner.isFilterDone() && results.isEmpty()) {
        // If the scanner's filter - if any - is done with the scan
        // only set moreResults to false if the results is empty. This is used to keep compatible
        // with the old scan implementation where we just ignore the returned results if moreResults
        // is false. Can remove the isEmpty check after we get rid of the old implementation.
        builder.setMoreResults(false);
      }
      // we only set moreResults to false in the above code, so set it to true if we haven't set it
      // yet.
      if (!builder.hasMoreResults()) {
        builder.setMoreResults(true);
      }
      if (builder.getMoreResults() && builder.getMoreResultsInRegion() && !results.isEmpty()) {
        // Record the last cell of the last result if it is a partial result
        // We need this to calculate the complete rows we have returned to client as the
        // mayHaveMoreCellsInRow is true does not mean that there will be extra cells for the
        // current row. We may filter out all the remaining cells for the current row and just
        // return the cells of the nextRow when calling RegionScanner.nextRaw. So here we need to
        // check for row change.
        Result lastResult = results.get(results.size() - 1);
        if (lastResult.mayHaveMoreCellsInRow()) {
          rsh.rowOfLastPartialResult = lastResult.getRow();
        } else {
          rsh.rowOfLastPartialResult = null;
        }
      }
      if (!builder.getMoreResults() || !builder.getMoreResultsInRegion() || closeScanner) {
        scannerClosed = true;
        closeScanner(region, scanner, scannerName, context);
      }
      return builder.build();
    } catch (Exception e) {
      try {
        // scanner is closed here
        scannerClosed = true;
        // The scanner state might be left in a dirty state, so we will tell the Client to
        // fail this RPC and close the scanner while opening up another one from the start of
        // row that the client has last seen.
        closeScanner(region, scanner, scannerName, context);

        // If it is a DoNotRetryIOException already, throw as it is. Unfortunately, DNRIOE is
        // used in two different semantics.
        // (1) The first is to close the client scanner and bubble up the exception all the way
        // to the application. This is preferred when the exception is really un-recoverable
        // (like CorruptHFileException, etc). Plain DoNotRetryIOException also falls into this
        // bucket usually.
        // (2) Second semantics is to close the current region scanner only, but continue the
        // client scanner by overriding the exception. This is usually UnknownScannerException,
        // OutOfOrderScannerNextException, etc where the region scanner has to be closed, but the
        // application-level ClientScanner has to continue without bubbling up the exception to
        // the client. See ClientScanner code to see how it deals with these special exceptions.
        if (e instanceof DoNotRetryIOException) {
          throw e;
        }

        // If it is a FileNotFoundException, wrap as a
        // DoNotRetryIOException. This can avoid the retry in ClientScanner.
        if (e instanceof FileNotFoundException) {
          throw new DoNotRetryIOException(e);
        }

        // We closed the scanner already. Instead of throwing the IOException, and client
        // retrying with the same scannerId only to get USE on the next RPC, we directly throw
        // a special exception to save an RPC.
        if (VersionInfoUtil.hasMinimumVersion(context.getClientVersionInfo(), 1, 4)) {
          // 1.4.0+ clients know how to handle
          throw new ScannerResetException("Scanner is closed on the server-side", e);
        } else {
          // older clients do not know about SRE. Just throw USE, which they will handle
          throw new UnknownScannerException("Throwing UnknownScannerException to reset the client"
              + " scanner state for clients older than 1.3.", e);
        }
      } catch (IOException ioe) {
        throw new ServiceException(ioe);
      }
    } finally {
      if (!scannerClosed) {
        // Adding resets expiration time on lease.
        // the closeCallBack will be set in closeScanner so here we only care about shippedCallback
        if (context != null) {
          context.setCallBack(rsh.shippedCallback);
        } else {
          // When context != null, adding back the lease will be done in callback set above.
          addScannerLeaseBack(lease);
        }
      }
      quota.close();
    }
  }

  private void closeScanner(Region region, RegionScanner scanner, String scannerName,
      RpcCallContext context) throws IOException {
    if (region.getCoprocessorHost() != null) {
      if (region.getCoprocessorHost().preScannerClose(scanner)) {
        // bypass the actual close.
        return;
      }
    }
    RegionScannerHolder rsh = scanners.remove(scannerName);
    if (rsh != null) {
      if (context != null) {
        context.setCallBack(rsh.closeCallBack);
      } else {
        rsh.s.close();
      }
      if (region.getCoprocessorHost() != null) {
        region.getCoprocessorHost().postScannerClose(scanner);
      }
    }
  }

  @Override
  public CoprocessorServiceResponse execRegionServerService(RpcController controller,
      CoprocessorServiceRequest request) throws ServiceException {
    return regionServer.execRegionServerService(controller, request);
  }

  @Override
  public UpdateConfigurationResponse updateConfiguration(
      RpcController controller, UpdateConfigurationRequest request)
      throws ServiceException {
    try {
      this.regionServer.updateConfiguration();
    } catch (Exception e) {
      throw new ServiceException(e);
    }
    return UpdateConfigurationResponse.getDefaultInstance();
  }

}
