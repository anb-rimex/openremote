/*
 * Copyright 2018, OpenRemote Inc.
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
package org.openremote.model.rules.json;

import org.openremote.model.query.filter.StringPredicate;

/**
 * This defines when an {@link org.openremote.model.rules.AssetState} becomes eligible for triggering the rule again once
 * if has triggered a rule.
 * <p>
 * There is an implicit OR condition between each option.
 */
public class RuleTriggerReset {

    /**
     * Simple timer expression e.g. '1h'
     */
    public String timer;

    /**
     * When the {@link Rule#when} {@link org.openremote.model.rules.json.predicate.AssetPredicate} evaluates to true.
     */
    public boolean triggerNoLongerMatches;

    /**
     * When the timestamp of the {@link org.openremote.model.rules.AssetState} changes in comparison to the timestamp
     * at the time the rule fired.
     */
    public StringPredicate attributeTimestampChange;

    /**
     * When the value of the {@link org.openremote.model.rules.AssetState} changes in comparison to the value at the
     * time the rule fired.
     */
    public StringPredicate attributeValueChange;
}
