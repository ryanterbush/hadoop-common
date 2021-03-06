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
package org.apache.hadoop.hdfs.server.namenode;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Random;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.BlockLocation;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.client.HdfsDataOutputStream;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.server.datanode.DataNode;
import org.junit.Test;
import org.junit.AfterClass;
import org.junit.BeforeClass;


public class TestFavoredNodesEndToEnd {
  private static MiniDFSCluster cluster;
  private static Configuration conf;
  private final static int NUM_DATA_NODES = 10;
  private final static int NUM_FILES = 10;
  private final static byte[] SOME_BYTES = new String("foo").getBytes();
  private static DistributedFileSystem dfs;
  private static ArrayList<DataNode> datanodes;
  
  @BeforeClass
  public static void setUpBeforeClass() throws Exception {
    conf = new Configuration();
    cluster = new MiniDFSCluster.Builder(conf).numDataNodes(NUM_DATA_NODES)
        .build();
    cluster.waitClusterUp();
    dfs = cluster.getFileSystem();
    datanodes = cluster.getDataNodes();
  }

  @AfterClass
  public static void tearDownAfterClass() throws Exception {
    if (cluster != null) { 
      cluster.shutdown();
    }
  }

  @Test
  public void testFavoredNodesEndToEnd() throws Exception {
    //create 10 files with random preferred nodes
    for (int i = 0; i < NUM_FILES; i++) {
      Random rand = new Random(System.currentTimeMillis() + i);
      //pass a new created rand so as to get a uniform distribution each time
      //without too much collisions (look at the do-while loop in getDatanodes)
      InetSocketAddress datanode[] = getDatanodes(rand);
      Path p = new Path("/filename"+i);
      FSDataOutputStream out = dfs.create(p, FsPermission.getDefault(), true,
          4096, (short)3, (long)4096, null, datanode);
      out.write(SOME_BYTES);
      out.close();
      BlockLocation[] locations = 
          dfs.getClient().getBlockLocations(p.toUri().getPath(), 0, 
              Long.MAX_VALUE);
      //make sure we have exactly one block location, and three hosts
      assertTrue(locations.length == 1 && locations[0].getHosts().length == 3);
      //verify the files got created in the right nodes
      for (BlockLocation loc : locations) {
        String[] hosts = loc.getNames();
        String[] hosts1 = getStringForInetSocketAddrs(datanode);
        assertTrue(compareNodes(hosts, hosts1));
      }
    }
  }

  @Test
  public void testWhenFavoredNodesNotPresent() throws Exception {
    //when we ask for favored nodes but the nodes are not there, we should
    //get some other nodes. In other words, the write to hdfs should not fail
    //and if we do getBlockLocations on the file, we should see one blklocation
    //and three hosts for that
    Random rand = new Random(System.currentTimeMillis());
    InetSocketAddress arbitraryAddrs[] = new InetSocketAddress[3];
    for (int i = 0; i < 3; i++) {
      arbitraryAddrs[i] = getArbitraryLocalHostAddr();
    }
    Path p = new Path("/filename-foo-bar");
    FSDataOutputStream out = dfs.create(p, FsPermission.getDefault(), true,
        4096, (short)3, (long)4096, null, arbitraryAddrs);
    out.write(SOME_BYTES);
    out.close();
    BlockLocation[] locations = 
        dfs.getClient().getBlockLocations(p.toUri().getPath(), 0, 
            Long.MAX_VALUE);
    assertTrue(locations.length == 1 && locations[0].getHosts().length == 3);
  }

  @Test
  public void testWhenSomeNodesAreNotGood() throws Exception {
    //make some datanode not "good" so that even if the client prefers it,
    //the namenode would not give it as a replica to write to
    DatanodeInfo d = cluster.getNameNode().getNamesystem().getBlockManager()
           .getDatanodeManager().getDatanodeByXferAddr(
               datanodes.get(0).getXferAddress().getAddress().getHostAddress(), 
               datanodes.get(0).getXferAddress().getPort());
    //set the decommission status to true so that 
    //BlockPlacementPolicyDefault.isGoodTarget returns false for this dn
    d.setDecommissioned();
    InetSocketAddress addrs[] = new InetSocketAddress[3];
    for (int i = 0; i < 3; i++) {
      addrs[i] = datanodes.get(i).getXferAddress();
    }
    Path p = new Path("/filename-foo-bar-baz");
    FSDataOutputStream out = dfs.create(p, FsPermission.getDefault(), true,
        4096, (short)3, (long)4096, null, addrs);
    out.write(SOME_BYTES);
    out.close();
    BlockLocation[] locations = 
        dfs.getClient().getBlockLocations(p.toUri().getPath(), 0, 
            Long.MAX_VALUE);
    //reset the state
    d.stopDecommission();
    assertTrue(locations.length == 1 && locations[0].getHosts().length == 3);
    //also make sure that the datanode[0] is not in the list of hosts
    String datanode0 = 
        datanodes.get(0).getXferAddress().getAddress().getHostAddress()
        + ":" + datanodes.get(0).getXferAddress().getPort();
    for (int i = 0; i < 3; i++) {
      if (locations[0].getNames()[i].equals(datanode0)) {
        fail(datanode0 + " not supposed to be a replica for the block");
      }
    }
  }

  private String[] getStringForInetSocketAddrs(InetSocketAddress[] datanode) {
    String strs[] = new String[datanode.length];
    for (int i = 0; i < datanode.length; i++) {
      strs[i] = datanode[i].getAddress().getHostAddress() + ":" + 
       datanode[i].getPort();
    }
    return strs;
  }

  private boolean compareNodes(String[] dnList1, String[] dnList2) {
    for (int i = 0; i < dnList1.length; i++) {
      boolean matched = false;
      for (int j = 0; j < dnList2.length; j++) {
        if (dnList1[i].equals(dnList2[j])) {
          matched = true;
          break;
        }
      }
      if (matched == false) {
        fail(dnList1[i] + " not a favored node");
      }
    }
    return true;
  }

  private InetSocketAddress[] getDatanodes(Random rand) {
    //Get some unique random indexes
    int idx1 = rand.nextInt(NUM_DATA_NODES);
    int idx2;
    
    do {
      idx2 = rand.nextInt(NUM_DATA_NODES);
    } while (idx1 == idx2);
    
    int idx3;
    do {
      idx3 = rand.nextInt(NUM_DATA_NODES);
    } while (idx2 == idx3 || idx1 == idx3);
    
    InetSocketAddress[] addrs = new InetSocketAddress[3];
    addrs[0] = datanodes.get(idx1).getXferAddress();
    addrs[1] = datanodes.get(idx2).getXferAddress();
    addrs[2] = datanodes.get(idx3).getXferAddress();
    return addrs;
  }

  private InetSocketAddress getArbitraryLocalHostAddr() 
      throws UnknownHostException{
    Random rand = new Random(System.currentTimeMillis());
    int port = rand.nextInt(65535);
    while (true) {
      boolean conflict = false;
      for (DataNode d : datanodes) {
        if (d.getXferAddress().getPort() == port) {
          port = rand.nextInt(65535);
          conflict = true;
        }
      }
      if (conflict == false) {
        break;
      }
    }
    return new InetSocketAddress(InetAddress.getLocalHost(), port);
  }
}
