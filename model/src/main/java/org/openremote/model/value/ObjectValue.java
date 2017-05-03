/*
 * Copyright 2010 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.openremote.model.value;

import java.util.Optional;

/**
 * Object values can not have duplicate keys. For equality operations, objects
 * are equal if their key sets are equal and each key has the same value in both
 * instances.
 */
public interface ObjectValue extends Value {

    Optional<Value> get(String key);

    Optional<String> getString(String key);

    Optional<Boolean> getBoolean(String key);

    Optional<Double> getNumber(String key);

    Optional<ArrayValue> getArray(String key);

    Optional<ObjectValue> getObject(String key);

    String[] keys();

    void put(String key, Value value);

    void put(String key, String value);

    void put(String key, double value);

    void put(String key, boolean bool);

    boolean hasKey(String key);

    void remove(String key);
}
