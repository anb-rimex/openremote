package org.openremote.test.rules

import org.openremote.manager.rules.RulesService
import org.openremote.manager.rules.RulesetStorageService
import org.openremote.manager.setup.SetupService
import org.openremote.manager.setup.builtin.KeycloakDemoSetup
import org.openremote.manager.setup.builtin.ManagerDemoSetup
import org.openremote.model.rules.AssetRuleset
import org.openremote.model.rules.GlobalRuleset
import org.openremote.model.rules.RulesResource
import org.openremote.model.rules.TenantRuleset
import org.openremote.test.ManagerContainerTrait
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import javax.ws.rs.WebApplicationException

import static org.openremote.container.util.MapAccess.getString
import static org.openremote.manager.setup.AbstractKeycloakSetup.SETUP_ADMIN_PASSWORD
import static org.openremote.manager.setup.AbstractKeycloakSetup.SETUP_ADMIN_PASSWORD_DEFAULT
import static org.openremote.model.Constants.*
import static org.openremote.model.rules.Ruleset.Lang.GROOVY

class BasicRulesetResourceTest extends Specification implements ManagerContainerTrait {

    def "Access rules as superuser"() {
        given: "the server container is started"
        def serverPort = findEphemeralPort()
        def container = startContainer(defaultConfig(serverPort), defaultServices())
        def rulesetStorageService = container.getService(RulesetStorageService.class)
        def rulesService = container.getService(RulesService.class)
        def keycloakDemoSetup = container.getService(SetupService.class).getTaskOfType(KeycloakDemoSetup.class)
        def managerDemoSetup = container.getService(SetupService.class).getTaskOfType(ManagerDemoSetup.class)

        and: "some test rulesets have been imported"
        def rulesImport = new BasicRulesImport(rulesetStorageService, keycloakDemoSetup, managerDemoSetup)

        and: "an authenticated admin user"
        def accessToken = authenticate(
                container,
                MASTER_REALM,
                KEYCLOAK_CLIENT_ID,
                MASTER_REALM_ADMIN_USER,
                getString(container.getConfig(), SETUP_ADMIN_PASSWORD, SETUP_ADMIN_PASSWORD_DEFAULT)
        ).token

        and: "the ruleset resource"
        def rulesetResource = getClientTarget(serverUri(serverPort), MASTER_REALM, accessToken).proxy(RulesResource.class)

        expect: "the rules engines to be ready"
        new PollingConditions(initialDelay: 2, timeout: 10, delay: 1).eventually {
            rulesImport.assertEnginesReady(rulesService, keycloakDemoSetup, managerDemoSetup)
        }

        /* ############################################## READ ####################################### */

        when: "the global rules are retrieved"
        def ruleDefinitions = rulesetResource.getGlobalRulesets(null)

        then: "result should match"
        ruleDefinitions.length == 2
        ruleDefinitions[0].name == "Some global demo rules"
        ruleDefinitions[0].lang == GROOVY
        ruleDefinitions[0].rules == null // Don't retrieve the (large) rules data when getting a list of rule definitions

        when: "some tenant rules are retrieved"
        ruleDefinitions = rulesetResource.getTenantRulesets(null, keycloakDemoSetup.masterTenant.id)

        then: "result should match"
        ruleDefinitions.length == 1
        ruleDefinitions[0].name == "Some master tenant demo rules"
        ruleDefinitions[0].lang == GROOVY

        when: "some tenant rules in a non-authenticated realm are retrieved"
        ruleDefinitions = rulesetResource.getTenantRulesets(null, keycloakDemoSetup.customerATenant.id)

        then: "result should match"
        ruleDefinitions.length == 1
        ruleDefinitions[0].name == "Some customerA tenant demo rules"
        ruleDefinitions[0].enabled

        when: "some asset rules are retrieved"
        ruleDefinitions = rulesetResource.getAssetRulesets(null, managerDemoSetup.apartment2Id)

        then: "result should match"
        ruleDefinitions.length == 1
        ruleDefinitions[0].name == "Some apartment 2 demo rules"
        ruleDefinitions[0].lang == GROOVY

        /* ############################################## WRITE ####################################### */

        when: "global ruleset is created"
        def globalRuleset = new GlobalRuleset("Test global definition", "SomeRulesCode", GROOVY)
        rulesetResource.createGlobalRuleset(null, globalRuleset)
        def rulesetId = rulesetResource.getGlobalRulesets(null)[2].id
        globalRuleset = rulesetResource.getGlobalRuleset(null, rulesetId)
        def lastModified = globalRuleset.lastModified

        then: "result should match"
        globalRuleset.id == rulesetId
        globalRuleset.version == 0
        globalRuleset.createdOn.time < System.currentTimeMillis()
        lastModified.time < System.currentTimeMillis()
        globalRuleset.name == "Test global definition"
        globalRuleset.rules == "SomeRulesCode"
        globalRuleset.lang == GROOVY

        when: "a global ruleset is updated"
        globalRuleset.name = "Renamed test global definition"
        globalRuleset.rules = "SomeRulesCodeModified"
        rulesetResource.updateGlobalRuleset(null, rulesetId, globalRuleset)
        globalRuleset = rulesetResource.getGlobalRuleset(null, rulesetId)

        then: "result should match"
        globalRuleset.id == rulesetId
        globalRuleset.version > 0
        globalRuleset.createdOn.time < System.currentTimeMillis()
        globalRuleset.lastModified.time > lastModified.time
        globalRuleset.name == "Renamed test global definition"
        globalRuleset.rules == "SomeRulesCodeModified"

        when: "a global ruleset is deleted"
        rulesetResource.deleteGlobalRuleset(null, rulesetId)
        globalRuleset = rulesetResource.getGlobalRuleset(null, rulesetId)

        then: "the result should be not found"
        WebApplicationException ex = thrown()
        ex.response.status == 404

        when: "a non-existent global ruleset is updated"
        rulesetResource.updateGlobalRuleset(null, 1234567890l, globalRuleset)

        then: "the result should be not found"
        ex = thrown()
        ex.response.status == 404

        when: "a tenant ruleset is created in the authenticated realm"
        def tenantRuleset = new TenantRuleset("Test tenant definition", keycloakDemoSetup.masterTenant.id, "SomeRulesCode", GROOVY)
        rulesetResource.createTenantRuleset(null, tenantRuleset)
        rulesetId = rulesetResource.getTenantRulesets(null, keycloakDemoSetup.masterTenant.id)[1].id
        tenantRuleset = rulesetResource.getTenantRuleset(null, rulesetId)
        lastModified = tenantRuleset.lastModified

        then: "result should match"
        tenantRuleset.id == rulesetId
        tenantRuleset.version == 0
        tenantRuleset.createdOn.time < System.currentTimeMillis()
        lastModified.time < System.currentTimeMillis()
        tenantRuleset.name == "Test tenant definition"
        tenantRuleset.rules == "SomeRulesCode"
        tenantRuleset.realmId == keycloakDemoSetup.masterTenant.id

        when: "a tenant ruleset is updated"
        tenantRuleset.name = "Renamed test tenant definition"
        tenantRuleset.rules = "SomeRulesCodeModified"
        rulesetResource.updateTenantRuleset(null, rulesetId, tenantRuleset)
        tenantRuleset = rulesetResource.getTenantRuleset(null, rulesetId)

        then: "result should match"
        tenantRuleset.id == rulesetId
        tenantRuleset.version == 1
        tenantRuleset.createdOn.time < System.currentTimeMillis()
        lastModified.time < System.currentTimeMillis()
        tenantRuleset.name == "Renamed test tenant definition"
        tenantRuleset.rules == "SomeRulesCodeModified"
        tenantRuleset.realmId == keycloakDemoSetup.masterTenant.id

        when: "a tenant ruleset is updated with an invalid realm"
        tenantRuleset.realmId = "thisdoesnotexist"
        rulesetResource.updateTenantRuleset(null, rulesetId, tenantRuleset)

        then: "the request should be bad"
        ex = thrown()
        ex.response.status == 400

        when: "a tenant ruleset is updated with an invalid id"
        tenantRuleset.realmId = keycloakDemoSetup.masterTenant.id
        tenantRuleset.id = 1234567890l
        rulesetResource.updateTenantRuleset(null, rulesetId, tenantRuleset)

        then: "the request should be bad"
        ex = thrown()
        ex.response.status == 400

        when: "a non-existent tenant ruleset is updated"
        rulesetResource.updateTenantRuleset(null, 1234567890l, tenantRuleset)

        then: "the result should be not found"
        ex = thrown()
        ex.response.status == 404

        when: "a tenant ruleset is deleted"
        rulesetResource.deleteTenantRuleset(null, rulesetId)
        rulesetResource.getTenantRuleset(null, rulesetId)

        then: "the result should be not found"
        ex = thrown()
        ex.response.status == 404

        when: "a tenant ruleset is created in a non-authenticated realm"
        tenantRuleset = new TenantRuleset("Test tenant definition", keycloakDemoSetup.customerATenant.id, "SomeRulesCode", GROOVY)
        rulesetResource.createTenantRuleset(null, tenantRuleset)
        rulesetId = rulesetResource.getTenantRulesets(null, keycloakDemoSetup.customerATenant.id)[1].id
        tenantRuleset = rulesetResource.getTenantRuleset(null, rulesetId)
        lastModified = tenantRuleset.lastModified

        then: "result should match"
        tenantRuleset.id == rulesetId
        tenantRuleset.version == 0
        tenantRuleset.createdOn.time < System.currentTimeMillis()
        lastModified.time < System.currentTimeMillis()
        tenantRuleset.name == "Test tenant definition"
        tenantRuleset.rules == "SomeRulesCode"
        tenantRuleset.realmId == keycloakDemoSetup.customerATenant.id

        when: "an asset ruleset is created in the authenticated realm"
        def assetRuleset = new AssetRuleset("Test asset definition", managerDemoSetup.smartOfficeId, "SomeRulesCode", GROOVY)
        rulesetResource.createAssetRuleset(null, assetRuleset)
        rulesetId = rulesetResource.getAssetRulesets(null, managerDemoSetup.smartOfficeId)[0].id
        assetRuleset = rulesetResource.getAssetRuleset(null, rulesetId)
        lastModified = assetRuleset.lastModified

        then: "result should match"
        assetRuleset.id == rulesetId
        assetRuleset.version == 0
        assetRuleset.createdOn.time < System.currentTimeMillis()
        lastModified.time < System.currentTimeMillis()
        assetRuleset.name == "Test asset definition"
        assetRuleset.rules == "SomeRulesCode"
        assetRuleset.assetId == managerDemoSetup.smartOfficeId

        when: "an asset ruleset is updated"
        assetRuleset.name = "Renamed test asset definition"
        assetRuleset.rules = "SomeRulesCodeModified"
        rulesetResource.updateAssetRuleset(null, rulesetId, assetRuleset)
        assetRuleset = rulesetResource.getAssetRuleset(null, rulesetId)

        then: "result should match"
        assetRuleset.id == rulesetId
        assetRuleset.version == 1
        assetRuleset.createdOn.time < System.currentTimeMillis()
        lastModified.time < System.currentTimeMillis()
        assetRuleset.name == "Renamed test asset definition"
        assetRuleset.rules == "SomeRulesCodeModified"
        assetRuleset.assetId == managerDemoSetup.smartOfficeId

        when: "an asset ruleset is updated with an invalid asset ID"
        assetRuleset.assetId = "thisdoesnotexist"
        rulesetResource.updateAssetRuleset(null, rulesetId, assetRuleset)

        then: "the request should be bad"
        ex = thrown()
        ex.response.status == 400

        when: "an asset ruleset is updated with an invalid id"
        assetRuleset.assetId = managerDemoSetup.smartOfficeId
        assetRuleset.id = 1234567890l
        rulesetResource.updateAssetRuleset(null, rulesetId, assetRuleset)

        then: "the request should be bad"
        ex = thrown()
        ex.response.status == 400

        when: "a non-existent asset ruleset is updated"
        rulesetResource.updateAssetRuleset(null, 1234567890l, assetRuleset)

        then: "the result should be not found"
        ex = thrown()
        ex.response.status == 404

        when: "an asset ruleset is deleted"
        rulesetResource.deleteAssetRuleset(null, rulesetId)
        rulesetResource.getAssetRuleset(null, rulesetId)

        then: "the result should be not found"
        ex = thrown()
        ex.response.status == 404

        when: "an asset ruleset is created in a non-authenticated realm"
        assetRuleset = new AssetRuleset("Test asset definition", managerDemoSetup.apartment2Id, "SomeRulesCode", GROOVY)
        rulesetResource.createAssetRuleset(null, assetRuleset)
        rulesetId = rulesetResource.getAssetRulesets(null, managerDemoSetup.apartment2Id)[1].id
        assetRuleset = rulesetResource.getAssetRuleset(null, rulesetId)
        lastModified = assetRuleset.lastModified

        then: "result should match"
        assetRuleset.id == rulesetId
        assetRuleset.version == 0
        assetRuleset.createdOn.time < System.currentTimeMillis()
        lastModified.time < System.currentTimeMillis()
        assetRuleset.name == "Test asset definition"
        assetRuleset.rules == "SomeRulesCode"
        assetRuleset.assetId == managerDemoSetup.apartment2Id

        cleanup: "the server should be stopped"
        stopContainer(container)
    }

    def "Access rules as testuser1"() {

        given: "the server container is started"
        def serverPort = findEphemeralPort()
        def container = startContainer(defaultConfig(serverPort), defaultServices())
        def rulesetStorageService = container.getService(RulesetStorageService.class)
        def rulesService = container.getService(RulesService.class)
        def keycloakDemoSetup = container.getService(SetupService.class).getTaskOfType(KeycloakDemoSetup.class)
        def managerDemoSetup = container.getService(SetupService.class).getTaskOfType(ManagerDemoSetup.class)

        and: "some imported rulesets"
        def rulesImport = new BasicRulesImport(rulesetStorageService, keycloakDemoSetup, managerDemoSetup)

        and: "an authenticated test user"
        def accessToken = authenticate(
                container,
                MASTER_REALM,
                KEYCLOAK_CLIENT_ID,
                "testuser1",
                "testuser1"
        ).token

        and: "the ruleset resource"
        def rulesetResource = getClientTarget(serverUri(serverPort), MASTER_REALM, accessToken).proxy(RulesResource.class)

        expect: "the rules engines to be ready"
        new PollingConditions(initialDelay: 3, timeout: 20, delay: 1).eventually {
            rulesImport.assertEnginesReady(rulesService, keycloakDemoSetup, managerDemoSetup)
        }

        /* ############################################## READ ####################################### */

        when: "the global rules are retrieved"
        rulesetResource.getGlobalRulesets(null)

        then: "access should be forbidden"
        WebApplicationException ex = thrown()
        ex.response.status == 403

        when: "some tenant rules are retrieved"
        def ruleDefinitions = rulesetResource.getTenantRulesets(null, keycloakDemoSetup.masterTenant.id)

        then: "result should match"
        ruleDefinitions.length == 1
        ruleDefinitions[0].name == "Some master tenant demo rules"

        when: "some tenant rules in a non-authenticated realm are retrieved"
        rulesetResource.getTenantRulesets(null, keycloakDemoSetup.customerATenant.id)

        then: "access should be forbidden"
        ex = thrown()
        ex.response.status == 403

        when: "some asset rules in a non-authenticated realm are retrieved"
        rulesetResource.getAssetRulesets(null, managerDemoSetup.apartment2Id)

        then: "access should be forbidden"
        ex = thrown()
        ex.response.status == 403

        /* ############################################## WRITE ####################################### */

        when: "a global rule is created"
        def ruleDefinition = new GlobalRuleset("Test definition", "SomeRulesCode", GROOVY)
        rulesetResource.createGlobalRuleset(null, ruleDefinition)

        then: "access should be forbidden"
        ex = thrown()
        ex.response.status == 403

        when: "a global rule is updated"
        rulesetResource.updateGlobalRuleset(null, 1234567890l, ruleDefinition)

        then: "access should be forbidden"
        ex = thrown()
        ex.response.status == 403

        when: "a global rule is deleted"
        rulesetResource.deleteGlobalRuleset(null, 1234567890l)

        then: "access should be forbidden"
        ex = thrown()
        ex.response.status == 403

        when: "a tenant ruleset is created in the authenticated realm"
        def tenantRuleset = new TenantRuleset("Test tenant definition", keycloakDemoSetup.masterTenant.id, "SomeRulesCode", GROOVY)
        rulesetResource.createTenantRuleset(null, tenantRuleset)
        def rulesetId = rulesetResource.getTenantRulesets(null, keycloakDemoSetup.masterTenant.id)[1].id
        tenantRuleset = rulesetResource.getTenantRuleset(null, rulesetId)
        def lastModified = tenantRuleset.lastModified

        then: "result should match"
        tenantRuleset.id == rulesetId
        tenantRuleset.version == 0
        tenantRuleset.createdOn.time < System.currentTimeMillis()
        lastModified.time < System.currentTimeMillis()
        tenantRuleset.name == "Test tenant definition"
        tenantRuleset.rules == "SomeRulesCode"
        tenantRuleset.realmId == keycloakDemoSetup.masterTenant.id

        when: "a tenant ruleset is updated"
        tenantRuleset.name = "Renamed test tenant definition"
        tenantRuleset.rules = "SomeRulesCodeModified"
        rulesetResource.updateTenantRuleset(null, rulesetId, tenantRuleset)
        tenantRuleset = rulesetResource.getTenantRuleset(null, rulesetId)

        then: "result should match"
        tenantRuleset.id == rulesetId
        tenantRuleset.version == 1
        tenantRuleset.createdOn.time < System.currentTimeMillis()
        lastModified.time < System.currentTimeMillis()
        tenantRuleset.name == "Renamed test tenant definition"
        tenantRuleset.rules == "SomeRulesCodeModified"
        tenantRuleset.realmId == keycloakDemoSetup.masterTenant.id

        when: "a tenant ruleset is updated with an invalid realm"
        tenantRuleset.realmId = "thisdoesnotexist"
        rulesetResource.updateTenantRuleset(null, rulesetId, tenantRuleset)

        then: "the request should be bad"
        ex = thrown()
        ex.response.status == 400

        when: "a tenant ruleset is updated with an invalid id"
        tenantRuleset.realmId = keycloakDemoSetup.masterTenant.id
        tenantRuleset.id = 1234567890l
        rulesetResource.updateTenantRuleset(null, rulesetId, tenantRuleset)

        then: "the request should be bad"
        ex = thrown()
        ex.response.status == 400

        when: "a non-existent tenant ruleset is updated"
        rulesetResource.updateTenantRuleset(null, 1234567890l, tenantRuleset)

        then: "the result should be not found"
        ex = thrown()
        ex.response.status == 404

        when: "a tenant ruleset is deleted"
        rulesetResource.deleteTenantRuleset(null, rulesetId)
        rulesetResource.getTenantRuleset(null, rulesetId)

        then: "the result should be not found"
        ex = thrown()
        ex.response.status == 404

        when: "a tenant ruleset is created in a non-authenticated realm"
        tenantRuleset = new TenantRuleset("Test tenant definition", keycloakDemoSetup.customerATenant.id, "SomeRulesCode", GROOVY)
        rulesetResource.createTenantRuleset(null, tenantRuleset)

        then: "access should be forbidden"
        ex = thrown()
        ex.response.status == 403

        when: "an asset ruleset is created in the authenticated realm"
        def assetRuleset = new AssetRuleset("Test asset definition", managerDemoSetup.smartOfficeId, "SomeRulesCode", GROOVY)
        rulesetResource.createAssetRuleset(null, assetRuleset)
        rulesetId = rulesetResource.getAssetRulesets(null, managerDemoSetup.smartOfficeId)[0].id
        assetRuleset = rulesetResource.getAssetRuleset(null, rulesetId)
        lastModified = assetRuleset.lastModified

        then: "result should match"
        assetRuleset.id == rulesetId
        assetRuleset.version == 0
        assetRuleset.createdOn.time < System.currentTimeMillis()
        lastModified.time < System.currentTimeMillis()
        assetRuleset.name == "Test asset definition"
        assetRuleset.rules == "SomeRulesCode"
        assetRuleset.assetId == managerDemoSetup.smartOfficeId

        when: "an asset ruleset is updated"
        assetRuleset.name = "Renamed test asset definition"
        assetRuleset.rules = "SomeRulesCodeModified"
        rulesetResource.updateAssetRuleset(null, rulesetId, assetRuleset)
        assetRuleset = rulesetResource.getAssetRuleset(null, rulesetId)

        then: "result should match"
        assetRuleset.id == rulesetId
        assetRuleset.version == 1
        assetRuleset.createdOn.time < System.currentTimeMillis()
        lastModified.time < System.currentTimeMillis()
        assetRuleset.name == "Renamed test asset definition"
        assetRuleset.rules == "SomeRulesCodeModified"
        assetRuleset.assetId == managerDemoSetup.smartOfficeId

        when: "an asset ruleset is updated with an invalid asset ID"
        assetRuleset.assetId = "thisdoesnotexist"
        rulesetResource.updateAssetRuleset(null, rulesetId, assetRuleset)

        then: "the request should be bad"
        ex = thrown()
        ex.response.status == 400

        when: "an asset ruleset is updated with an invalid id"
        assetRuleset.assetId = managerDemoSetup.smartOfficeId
        assetRuleset.id = 1234567890l
        rulesetResource.updateAssetRuleset(null, rulesetId, assetRuleset)

        then: "the request should be bad"
        ex = thrown()
        ex.response.status == 400

        when: "a non-existent asset ruleset is updated"
        rulesetResource.updateAssetRuleset(null, 1234567890l, assetRuleset)

        then: "the result should be not found"
        ex = thrown()
        ex.response.status == 404

        when: "an asset ruleset is deleted"
        rulesetResource.deleteAssetRuleset(null, rulesetId)
        rulesetResource.getAssetRuleset(null, rulesetId)

        then: "the result should be not found"
        ex = thrown()
        ex.response.status == 404

        when: "an asset ruleset is created in a non-authenticated realm"
        assetRuleset = new AssetRuleset("Test asset definition", managerDemoSetup.apartment2Id, "SomeRulesCode", GROOVY)
        rulesetResource.createAssetRuleset(null, assetRuleset)

        then: "access should be forbidden"
        ex = thrown()
        ex.response.status == 403

        cleanup: "the server should be stopped"
        stopContainer(container)
    }

    def "Access rules as testuser2"() {

        given: "the server container is started"
        def serverPort = findEphemeralPort()
        def container = startContainer(defaultConfig(serverPort), defaultServices())
        def rulesetStorageService = container.getService(RulesetStorageService.class)
        def rulesService = container.getService(RulesService.class)
        def keycloakDemoSetup = container.getService(SetupService.class).getTaskOfType(KeycloakDemoSetup.class)
        def managerDemoSetup = container.getService(SetupService.class).getTaskOfType(ManagerDemoSetup.class)

        and: "some imported rulesets"
        def rulesImport = new BasicRulesImport(rulesetStorageService, keycloakDemoSetup, managerDemoSetup)

        and: "an authenticated test user"
        def accessToken = authenticate(
                container,
                keycloakDemoSetup.customerATenant.realm,
                KEYCLOAK_CLIENT_ID,
                "testuser2",
                "testuser2"
        ).token

        and: "the ruleset resource"
        def rulesetResource = getClientTarget(serverUri(serverPort), keycloakDemoSetup.customerATenant.realm, accessToken).proxy(RulesResource.class)

        expect: "the rules engines to be ready"
        new PollingConditions(initialDelay: 3, timeout: 20, delay: 1).eventually {
            rulesImport.assertEnginesReady(rulesService, keycloakDemoSetup, managerDemoSetup)
        }

        /* ############################################## READ ####################################### */

        when: "the global rules are retrieved"
        rulesetResource.getGlobalRulesets(null)

        then: "access should be forbidden"
        WebApplicationException ex = thrown()
        ex.response.status == 403

        when: "some tenant rules in a non-authenticated realm are retrieved"
        rulesetResource.getTenantRulesets(null, keycloakDemoSetup.masterTenant.id)

        then: "access should be forbidden"
        ex = thrown()
        ex.response.status == 403

        when: "some tenant rules in the authenticated realm are retrieved"
        rulesetResource.getTenantRulesets(null, keycloakDemoSetup.customerATenant.id)

        then: "access should be forbidden"
        ex = thrown()
        ex.response.status == 403

        when: "some asset rules in the authenticated realm are retrieved"
        rulesetResource.getAssetRulesets(null, managerDemoSetup.apartment2Id)

        then: "access should be forbidden"
        ex = thrown()
        ex.response.status == 403

        /* ############################################## WRITE ####################################### */

        when: "a global rule is created"
        def ruleDefinition = new GlobalRuleset("Test definition", "SomeRulesCode", GROOVY)
        rulesetResource.createGlobalRuleset(null, ruleDefinition)

        then: "access should be forbidden"
        ex = thrown()
        ex.response.status == 403

        when: "a global rule is updated"
        rulesetResource.updateGlobalRuleset(null, 1234567890l, ruleDefinition)

        then: "access should be forbidden"
        ex = thrown()
        ex.response.status == 403

        when: "a global rule is deleted"
        rulesetResource.deleteGlobalRuleset(null, 1234567890l)

        then: "access should be forbidden"
        ex = thrown()
        ex.response.status == 403

        when: "a tenant ruleset is created in the authenticated realm"
        def tenantRuleset = new TenantRuleset("Test tenant definition", keycloakDemoSetup.customerATenant.id, "SomeRulesCode", GROOVY)
        rulesetResource.createTenantRuleset(null, tenantRuleset)

        then: "access should be forbidden"
        ex = thrown()
        ex.response.status == 403

        when: "a tenant ruleset is updated"
        rulesetResource.updateTenantRuleset(null, 1234567890l, tenantRuleset)

        then: "access should be forbidden"
        ex = thrown()
        ex.response.status == 403

        when: "a tenant ruleset is deleted"
        rulesetResource.deleteTenantRuleset(null, 1234567890l)

        then: "access should be forbidden"
        ex = thrown()
        ex.response.status == 403

        when: "a tenant ruleset is created in a non-authenticated realm"
        tenantRuleset = new TenantRuleset("Test tenant definition", keycloakDemoSetup.customerBTenant.id, "SomeRulesCode", GROOVY)
        rulesetResource.createTenantRuleset(null, tenantRuleset)

        then: "access should be forbidden"
        ex = thrown()
        ex.response.status == 403

        when: "a tenant ruleset is created in the authenticated realm"
        def assetRuleset = new AssetRuleset("Test asset definition", keycloakDemoSetup.customerATenant.id, "SomeRulesCode", GROOVY)
        rulesetResource.createAssetRuleset(null, assetRuleset)

        then: "access should be forbidden"
        ex = thrown()
        ex.response.status == 403

        when: "a tenant ruleset is updated"
        rulesetResource.updateAssetRuleset(null, 1234567890l, assetRuleset)

        then: "access should be forbidden"
        ex = thrown()
        ex.response.status == 403

        when: "a tenant ruleset is deleted"
        rulesetResource.deleteAssetRuleset(null, 1234567890l)

        then: "access should be forbidden"
        ex = thrown()
        ex.response.status == 403

        when: "a tenant ruleset is created in a non-authenticated realm"
        assetRuleset = new AssetRuleset("Test asset definition", keycloakDemoSetup.customerBTenant.id, "SomeRulesCode", GROOVY)
        rulesetResource.createAssetRuleset(null, assetRuleset)

        then: "access should be forbidden"
        ex = thrown()
        ex.response.status == 403

        cleanup: "the server should be stopped"
        stopContainer(container)
    }

    def "Access rules as testuser3"() {

        given: "the server container is started"
        def serverPort = findEphemeralPort()
        def container = startContainer(defaultConfig(serverPort), defaultServices())
        def rulesetStorageService = container.getService(RulesetStorageService.class)
        def rulesService = container.getService(RulesService.class)
        def keycloakDemoSetup = container.getService(SetupService.class).getTaskOfType(KeycloakDemoSetup.class)
        def managerDemoSetup = container.getService(SetupService.class).getTaskOfType(ManagerDemoSetup.class)

        and: "some imported rulesets"
        def rulesImport = new BasicRulesImport(rulesetStorageService, keycloakDemoSetup, managerDemoSetup)

        and: "an authenticated test user"
        def accessToken = authenticate(
                container,
                keycloakDemoSetup.customerATenant.realm,
                KEYCLOAK_CLIENT_ID,
                "testuser3",
                "testuser3"
        ).token

        and: "the ruleset resource"
        def rulesetResource = getClientTarget(serverUri(serverPort), keycloakDemoSetup.customerATenant.realm, accessToken).proxy(RulesResource.class)

        expect: "the rules engines to be ready"
        new PollingConditions(initialDelay: 3, timeout: 20, delay: 1).eventually {
            rulesImport.assertEnginesReady(rulesService, keycloakDemoSetup, managerDemoSetup)
        }

        /* ############################################## READ ####################################### */

        when: "the global rules are retrieved"
        rulesetResource.getGlobalRulesets(null)

        then: "access should be forbidden"
        WebApplicationException ex = thrown()
        ex.response.status == 403

        when: "some tenant rules in a non-authenticated realm are retrieved"
        rulesetResource.getTenantRulesets(null, keycloakDemoSetup.masterTenant.id)

        then: "access should be forbidden"
        ex = thrown()
        ex.response.status == 403

        when: "some tenant rules in the authenticated realm are retrieved"
        rulesetResource.getTenantRulesets(null, keycloakDemoSetup.customerATenant.id)

        then: "access should be forbidden"
        ex = thrown()
        ex.response.status == 403

        when: "some asset rules of a protected assigned asset are retrieved"
        def ruleDefinitions = rulesetResource.getAssetRulesets(null, managerDemoSetup.apartment2Id)

        then: "result should match"
        ruleDefinitions.length == 1
        ruleDefinitions[0].name == "Some apartment 2 demo rules"

        when: "some asset rules of a protected but not assigned asset are retrieved"
        rulesetResource.getAssetRulesets(null, managerDemoSetup.apartment3Id)

        then: "access should be forbidden"
        ex = thrown()
        ex.response.status == 403

        /* ############################################## WRITE ####################################### */

        when: "a global rule is created"
        def ruleDefinition = new GlobalRuleset("Test definition", "SomeRulesCode", GROOVY)
        rulesetResource.createGlobalRuleset(null, ruleDefinition)

        then: "access should be forbidden"
        ex = thrown()
        ex.response.status == 403

        when: "a global rule is updated"
        rulesetResource.updateGlobalRuleset(null, 1234567890l, ruleDefinition)

        then: "access should be forbidden"
        ex = thrown()
        ex.response.status == 403

        when: "a global rule is deleted"
        rulesetResource.deleteGlobalRuleset(null, 1234567890l)

        then: "access should be forbidden"
        ex = thrown()
        ex.response.status == 403

        when: "a tenant ruleset is created in the authenticated realm"
        def tenantRuleset = new TenantRuleset("Test tenant definition", keycloakDemoSetup.customerATenant.id, "SomeRulesCode", GROOVY)
        rulesetResource.createTenantRuleset(null, tenantRuleset)

        then: "access should be forbidden"
        ex = thrown()
        ex.response.status == 403

        when: "a tenant ruleset is updated"
        rulesetResource.updateTenantRuleset(null, 1234567890l, tenantRuleset)

        then: "result should be not found"
        ex = thrown()
        ex.response.status == 404

        when: "a tenant ruleset is deleted"
        rulesetResource.deleteTenantRuleset(null, 1234567890l)
        rulesetResource.getTenantRuleset(null, 1234567890l)

        then: "the result should be not found"
        ex = thrown()
        ex.response.status == 404

        when: "a tenant ruleset is created in a non-authenticated realm"
        tenantRuleset = new TenantRuleset("Test tenant definition", keycloakDemoSetup.customerBTenant.id, "SomeRulesCode", GROOVY)
        rulesetResource.createTenantRuleset(null, tenantRuleset)

        then: "access should be forbidden"
        ex = thrown()
        ex.response.status == 403

        when: "an asset ruleset is created in the authenticated realm"
        def assetRuleset = new AssetRuleset("Test asset definition", managerDemoSetup.apartment2Id, "SomeRulesCode", GROOVY)
        rulesetResource.createAssetRuleset(null, assetRuleset)
        def rulesetId = rulesetResource.getAssetRulesets(null, managerDemoSetup.apartment2Id)[1].id
        assetRuleset = rulesetResource.getAssetRuleset(null, rulesetId)
        def lastModified = assetRuleset.lastModified

        then: "result should match"
        assetRuleset.id == rulesetId
        assetRuleset.version == 0
        assetRuleset.createdOn.time < System.currentTimeMillis()
        lastModified.time < System.currentTimeMillis()
        assetRuleset.name == "Test asset definition"
        assetRuleset.rules == "SomeRulesCode"
        assetRuleset.assetId == managerDemoSetup.apartment2Id

        when: "an asset ruleset is updated"
        assetRuleset.name = "Renamed test asset definition"
        assetRuleset.rules = "SomeRulesCodeModified"
        rulesetResource.updateAssetRuleset(null, rulesetId, assetRuleset)
        assetRuleset = rulesetResource.getAssetRuleset(null, rulesetId)

        then: "result should match"
        assetRuleset.id == rulesetId
        assetRuleset.version == 1
        assetRuleset.createdOn.time < System.currentTimeMillis()
        lastModified.time < System.currentTimeMillis()
        assetRuleset.name == "Renamed test asset definition"
        assetRuleset.rules == "SomeRulesCodeModified"
        assetRuleset.assetId == managerDemoSetup.apartment2Id

        when: "an asset ruleset is updated with a changed asset ID"
        assetRuleset.assetId = managerDemoSetup.apartment3Id
        rulesetResource.updateAssetRuleset(null, rulesetId, assetRuleset)

        then: "the request should be bad"
        ex = thrown()
        ex.response.status == 400

        when: "an asset ruleset is updated with a changed invalid asset ID"
        assetRuleset.assetId = "thisdoesnotexist"
        rulesetResource.updateAssetRuleset(null, rulesetId, assetRuleset)

        then: "the request should be bad"
        ex = thrown()
        ex.response.status == 400

        when: "an asset ruleset is updated with an invalid id"
        assetRuleset.assetId = managerDemoSetup.apartment2Id
        assetRuleset.id = 1234567890l
        rulesetResource.updateAssetRuleset(null, rulesetId, assetRuleset)

        then: "the request should be bad"
        ex = thrown()
        ex.response.status == 400

        when: "a non-existent asset ruleset is updated"
        rulesetResource.updateAssetRuleset(null, 1234567890l, assetRuleset)

        then: "the result should be not found"
        ex = thrown()
        ex.response.status == 404

        when: "an asset ruleset is deleted"
        rulesetResource.deleteAssetRuleset(null, rulesetId)
        rulesetResource.getAssetRuleset(null, rulesetId)

        then: "the result should be not found"
        ex = thrown()
        ex.response.status == 404

        when: "an asset ruleset is created in the authenticated realm but on a forbidden asset "
        assetRuleset = new AssetRuleset("Test asset definition", managerDemoSetup.apartment3Id, "SomeRulesCode", GROOVY)
        rulesetResource.createAssetRuleset(null, assetRuleset)

        then: "access should be forbidden"
        ex = thrown()
        ex.response.status == 403

        when: "an asset ruleset is created in a non-authenticated realm"
        assetRuleset = new AssetRuleset("Test asset definition", managerDemoSetup.smartOfficeId, "SomeRulesCode", GROOVY)
        rulesetResource.createAssetRuleset(null, assetRuleset)

        then: "access should be forbidden"
        ex = thrown()
        ex.response.status == 403

        cleanup: "the server should be stopped"
        stopContainer(container)
    }
}
