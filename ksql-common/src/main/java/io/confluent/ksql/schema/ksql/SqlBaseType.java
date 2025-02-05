/*
 * Copyright 2019 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.ksql.schema.ksql;

/**
 * The SQL types supported by KSQL.
 */
public enum SqlBaseType {
  BOOLEAN, INTEGER, BIGINT, DOUBLE, DECIMAL, STRING, ARRAY, MAP, STRUCT;

  public boolean isNumber() {
    // for now, conversions between DECIMAL and other numeric types is not supported
    return this == INTEGER || this == BIGINT || this == DOUBLE;
  }

  public boolean canUpCast(final SqlBaseType to) {
    return isNumber() && this.ordinal() <= to.ordinal();
  }
}
