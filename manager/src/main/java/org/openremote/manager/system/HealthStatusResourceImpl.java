/*
 * Copyright 2017, OpenRemote Inc.
 *
 * See the CONTRIBUTORS.txt file in the distribution for a
 * full listing of individual contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.openremote.manager.system;

import org.openremote.model.system.HealthStatusProvider;
import org.openremote.model.system.HealthStatusResource;
import org.openremote.model.value.ObjectValue;
import org.openremote.model.value.Values;

import java.util.List;

public class HealthStatusResourceImpl implements HealthStatusResource {

    protected List<HealthStatusProvider> healthStatusProviderList;

    public HealthStatusResourceImpl(List<HealthStatusProvider> healthStatusProviderList) {
        this.healthStatusProviderList = healthStatusProviderList;
    }

    @Override
    public ObjectValue getHealthStatus() {
        ObjectValue objectValue = Values.createObject();

        healthStatusProviderList.forEach(healthStatusProvider -> {
                ObjectValue providerValue = Values.createObject();
                providerValue.put("version", healthStatusProvider.getHealthStatusVersion());
                providerValue.put("data", healthStatusProvider.getHealthStatus());
                objectValue.put(healthStatusProvider.getHealthStatusName(), providerValue);
            }
        );

        return objectValue;
    }
}
