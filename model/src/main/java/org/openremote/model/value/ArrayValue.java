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
import java.util.stream.Stream;

public interface ArrayValue extends Value {

    Optional<Value> get(int index);

    Optional<String> getString(int index);

    Optional<Boolean> getBoolean(int index);

    Optional<Double> getNumber(int index);

    Optional<ArrayValue> getArray(int index);

    Optional<ObjectValue> getObject(int index);

    int length();

    void remove(int index);

    void set(int index, Value value);

    void set(int index, String string);

    void set(int index, double number);

    void set(int index, boolean bool);
}
