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
package org.apache.ratis.server.impl;

import org.apache.log4j.Level;
import org.apache.ratis.BaseTest;
import org.apache.ratis.RaftTestUtil;
import org.apache.ratis.client.RaftClient;
import org.apache.ratis.client.RaftClientRpc;
import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.protocol.RaftClientReply;
import org.apache.ratis.protocol.RaftClientRequest;
import org.apache.ratis.protocol.exceptions.StateMachineException;
import org.apache.ratis.server.RaftServer;
import org.apache.ratis.server.raftlog.RaftLog;
import org.apache.ratis.statemachine.SimpleStateMachine4Testing;
import org.apache.ratis.statemachine.StateMachine;
import org.apache.ratis.statemachine.TransactionContext;
import org.apache.ratis.util.Log4jUtils;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;

/**
 * Tests the leader step down flag in {@link StateMachineException}, which
 * determines whether the leader should step down or not when it receives the
 * exception from {@link StateMachine#preAppendTransaction}.
 */
public abstract class PreAppendLeaderStepDownTest<CLUSTER extends MiniRaftCluster>
    extends BaseTest implements MiniRaftCluster.Factory.Get<CLUSTER> {
  {
    Log4jUtils.setLogLevel(RaftServer.Division.LOG, Level.DEBUG);
    Log4jUtils.setLogLevel(RaftLog.LOG, Level.DEBUG);
    Log4jUtils.setLogLevel(RaftClient.LOG, Level.DEBUG);
  }

  private static volatile boolean leaderShouldStepDown = false;

  protected static class StateMachineWithException extends
      SimpleStateMachine4Testing {

    @Override
    public TransactionContext preAppendTransaction(TransactionContext trx)
        throws IOException {
      StateMachineException ex = new StateMachineException(
          "Fake Exception in preAppend");
      if (!leaderShouldStepDown) {
        ex.setLeaderShouldStepDown(false);
        // If leader should step down, do not change the exception state to
        // ensure that step down being true is the default behavior.
      }
      throw ex;
    }
  }

  {
    final RaftProperties prop = getProperties();
    prop.setClass(MiniRaftCluster.STATEMACHINE_CLASS_KEY, StateMachineWithException.class, StateMachine.class);
  }

  @Test
  public void testLeaderStepDown() throws Exception {
    leaderShouldStepDown = true;
    runWithNewCluster(3, this::runTestLeaderStepDown);
  }

  @Test
  public void testNoLeaderStepDown() throws Exception {
    leaderShouldStepDown = false;
    runWithNewCluster(3, this::runTestLeaderStepDown);
  }

  private void runTestLeaderStepDown(CLUSTER cluster) throws Exception {
    final RaftServer.Division oldLeader = RaftTestUtil.waitForLeader(cluster);
    try (final RaftClient client = cluster.createClient(oldLeader.getId())) {
      final RaftClientRpc rpc = client.getClientRpc();
      final long callId = 999;
      final RaftTestUtil.SimpleMessage message = new RaftTestUtil.SimpleMessage("message");
      RaftClientRequest r = cluster.newRaftClientRequest(client.getId(), oldLeader.getId(), callId, message);

      long oldTerm =
          RaftTestUtil.waitForLeader(cluster).getRaftLog().getLastEntryTermIndex().getTerm();

      // Cannot check the state machine exception attached to the reply for the
      // leader step down flag, because that flag is lost when the exception
      // is converted to and from a protobuf to pass back from the cluster to
      // the client.
      rpc.sendRequest(r);

      long newTerm =
          RaftTestUtil.waitForLeader(cluster).getRaftLog().getLastEntryTermIndex().getTerm();

      if (leaderShouldStepDown) {
        Assert.assertTrue(newTerm > oldTerm);
      } else {
        Assert.assertEquals(newTerm, oldTerm);
      }

      cluster.shutdown();
    }
  }
}
