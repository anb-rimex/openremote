package demo.rules

import org.openremote.manager.rules.RulesBuilder
import org.openremote.manager.rules.RulesFacts
import org.openremote.model.query.AssetQuery
import org.openremote.model.query.BaseAssetQuery.Operator
import org.openremote.model.rules.AssetState

import java.util.logging.Logger
import java.util.stream.Stream

import static org.openremote.model.asset.AssetType.RESIDENCE
import static org.openremote.model.asset.AssetType.ROOM
import static org.openremote.model.query.BaseAssetQuery.Operator.*

Logger LOG = binding.LOG
RulesBuilder rules = binding.rules

final VENTILATION_LEVEL_LOW = 64d
final VENTILATION_LEVEL_MEDIUM = 128d
final VENTILATION_LEVEL_HIGH = 255d

final VENTILATION_THRESHOLD_MEDIUM_CO2_TIME_WINDOW = "15m"
final VENTILATION_THRESHOLD_MEDIUM_CO2_LEVEL = 700d
final VENTILATION_THRESHOLD_MEDIUM_HUMIDITY_TIME_WINDOW = "1m"
final VENTILATION_THRESHOLD_MEDIUM_HUMIDITY = 70d

final VENTILATION_THRESHOLD_HIGH_CO2_TIME_WINDOW = "5m"
final VENTILATION_THRESHOLD_HIGH_CO2_LEVEL = 1000d
final VENTILATION_THRESHOLD_HIGH_HUMIDITY_TIME_WINDOW = "1m"
final VENTILATION_THRESHOLD_HIGH_HUMIDITY = 85d

final VENTILATION_THRESHOLD_MEDIUM_TIME_WINDOW = "30m"
final VENTILATION_THRESHOLD_HIGH_TIME_WINDOW = "15m"

Closure<Stream<AssetState>> residenceWithVentilationAutoMatch =
        { RulesFacts facts, Operator operator, double ventilationLevelThreshold ->
            // Any residence where auto ventilation is on
            facts.matchAssetState(
                    new AssetQuery().type(RESIDENCE).attributeValue("ventilationAuto", true)
            ).filter { residenceWithVentilationAuto ->
                facts.matchFirstAssetState(
                        // and ventilation level is above/below/equal/etc. threshold
                        new AssetQuery().id(residenceWithVentilationAuto.id)
                                .attributeValue("ventilationLevel", operator, ventilationLevelThreshold)
                ).isPresent()
            }
        }

Closure<Boolean> roomThresholdMatch =
        { RulesFacts facts, AssetState residence, String attribute, boolean above, double threshold, String timeWindow ->
            // Any room of the residence which has a level greater than zero
            def roomWithMaxLevel = facts.matchAssetState(
                    new AssetQuery().type(ROOM).parent(residence.id).attributeValue(attribute, GREATER_THAN, 0)
            ).max { roomA, roomB ->
                // get the room with the highest level
                Double.compare(roomA.valueAsNumber.orElse(0), roomB.valueAsNumber.orElse(0))
            }

            if (roomWithMaxLevel.isPresent()) {
                // if we have a room with max level
                return roomWithMaxLevel.filter { room ->
                    // and the level is above/below threshold
                    room.valueAsNumber.filter({ level -> above ? level > threshold : level <= threshold }).isPresent()
                }.filter { roomAboveBelowThreshold ->
                    // and no level above/below threshold in the time window
                    facts.matchAssetEvent(
                            new AssetQuery()
                                    .id(roomAboveBelowThreshold.id)
                                    .attributeValue(attribute, above ? LESS_EQUALS : GREATER_THAN, threshold)
                    ).filter(facts.clock.last(timeWindow)).count() == 0
                }.isPresent()
            } else {
                // if we don't have a room with max level (empty attribute?), it's always "below threshold"
                return !above
            }
        }

rules.add()
        .name("Auto ventilation is on, level is below MEDIUM, CO2 or humidity above MEDIUM, set level MEDIUM")
        .when(
        { facts ->
            residenceWithVentilationAutoMatch(
                    facts, LESS_THAN, VENTILATION_LEVEL_MEDIUM
            ).filter { residence ->
                def roomAboveThresholdCO2 = roomThresholdMatch(
                        facts, residence, "co2Level", true, VENTILATION_THRESHOLD_MEDIUM_CO2_LEVEL, VENTILATION_THRESHOLD_MEDIUM_CO2_TIME_WINDOW
                )
                def roomAboveThresholdHumidity = roomThresholdMatch(
                        facts, residence, "humidity", true, VENTILATION_THRESHOLD_MEDIUM_HUMIDITY, VENTILATION_THRESHOLD_MEDIUM_HUMIDITY_TIME_WINDOW
                )
                roomAboveThresholdCO2 || roomAboveThresholdHumidity
            }.findFirst().map { residence ->
                facts.bind("residence", residence)
                true
            }.orElse(false)
        })
        .then(
        { facts ->
            AssetState residence = facts.bound("residence")
            LOG.info("Ventilation auto and too much CO2/humidity in a room, switching to MEDIUM: " + residence.name)
            facts.updateAssetState(residence.id, "ventilationLevel", VENTILATION_LEVEL_MEDIUM)
        })

rules.add()
        .name("Auto ventilation is on, level is above LOW, CO2 or humidity below MEDIUM, set level LOW")
        .when(
        { facts ->
            residenceWithVentilationAutoMatch(
                    facts, GREATER_THAN, VENTILATION_LEVEL_LOW
            ).filter { residence ->
                def roomBelowThresholdCO2 = roomThresholdMatch(
                        facts, residence, "co2Level", false, VENTILATION_THRESHOLD_MEDIUM_CO2_LEVEL, VENTILATION_THRESHOLD_MEDIUM_TIME_WINDOW
                )
                def roomBelowThresholdHumidity = roomThresholdMatch(
                        facts, residence, "humidity", false, VENTILATION_THRESHOLD_MEDIUM_HUMIDITY, VENTILATION_THRESHOLD_MEDIUM_TIME_WINDOW
                )
                roomBelowThresholdCO2 && roomBelowThresholdHumidity
            }.findFirst().map { residence ->
                facts.bind("residence", residence)
                true
            }.orElse(false)
        })
        .then(
        { facts ->
            AssetState residence = facts.bound("residence")
            LOG.info("Ventilation auto and low CO2/humidity in all rooms, switching to LOW: " + residence.name)
            facts.updateAssetState(residence.id, "ventilationLevel", VENTILATION_LEVEL_LOW)
        })

rules.add()
        .name("Auto ventilation is on, level is below HIGH, CO2 or humidity above HIGH, set level HIGH")
        .when(
        { facts ->
            residenceWithVentilationAutoMatch(
                    facts, LESS_THAN, VENTILATION_LEVEL_HIGH
            ).filter { residence ->
                def roomAboveThresholdCO2 = roomThresholdMatch(
                        facts, residence, "co2Level", true, VENTILATION_THRESHOLD_HIGH_CO2_LEVEL, VENTILATION_THRESHOLD_HIGH_CO2_TIME_WINDOW
                )
                def roomAboveThresholdHumidity = roomThresholdMatch(
                        facts, residence, "humidity", true, VENTILATION_THRESHOLD_HIGH_HUMIDITY, VENTILATION_THRESHOLD_HIGH_HUMIDITY_TIME_WINDOW
                )
                roomAboveThresholdCO2 || roomAboveThresholdHumidity
            }.findFirst().map { residence ->
                facts.bind("residence", residence)
                true
            }.orElse(false)
        })
        .then(
        { facts ->
            AssetState residence = facts.bound("residence")
            LOG.info("Ventilation auto and too much CO2/humidity in a room, switching to HIGH: " + residence.name)
            facts.updateAssetState(residence.id, "ventilationLevel", VENTILATION_LEVEL_HIGH)
        })

rules.add()
        .name("Auto ventilation is on, level is above MEDIUM, CO2 or humidity below HIGH, set level MEDIUM")
        .when(
        { facts ->
            residenceWithVentilationAutoMatch(
                    facts, GREATER_THAN, VENTILATION_LEVEL_MEDIUM
            ).filter { residence ->
                def roomBelowThresholdCO2 = roomThresholdMatch(
                        facts, residence, "co2Level", false, VENTILATION_THRESHOLD_HIGH_CO2_LEVEL, VENTILATION_THRESHOLD_HIGH_TIME_WINDOW
                )
                def roomBelowThresholdHumidity = roomThresholdMatch(
                        facts, residence, "humidity", false, VENTILATION_THRESHOLD_HIGH_HUMIDITY, VENTILATION_THRESHOLD_HIGH_TIME_WINDOW
                )
                roomBelowThresholdCO2 && roomBelowThresholdHumidity
            }.findFirst().map { residence ->
                facts.bind("residence", residence)
                true
            }.orElse(false)
        })
        .then(
        { facts ->
            AssetState residence = facts.bound("residence")
            LOG.info("Ventilation auto and low CO2/humidity in all rooms, switching to MEDIUM: " + residence.name)
            facts.updateAssetState(residence.id, "ventilationLevel", VENTILATION_LEVEL_MEDIUM)
        })
