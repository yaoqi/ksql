/*
 * Copyright 2019 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"; you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.ksql.services;

import static io.confluent.ksql.util.LimitedProxyBuilder.methodParams;

import com.google.common.collect.ImmutableList;
import io.confluent.ksql.services.ConnectClient.ConnectResponse;
import io.confluent.ksql.util.LimitedProxyBuilder;
import java.util.Map;

/**
 * Supplies {@link ConnectClient}s to use that do not make any
 * state changes to the external connect clusters.
 */
final class SandboxConnectClient {

  private SandboxConnectClient() { }

  public static ConnectClient createProxy() {
    return LimitedProxyBuilder.forClass(ConnectClient.class)
        .swallow("create", methodParams(String.class, Map.class), ConnectResponse.of("sandbox"))
        .swallow("describe", methodParams(String.class), ConnectResponse.of("sandbox"))
        .swallow("connectors", methodParams(), ConnectResponse.of(ImmutableList.of()))
        .swallow("status", methodParams(String.class), ConnectResponse.of("sandbox"))
        .build();
  }
}
