package org.openremote.test.rules.residence

import com.google.common.collect.Lists
import com.google.firebase.messaging.Message
import org.openremote.container.timer.TimerService
import org.openremote.manager.asset.AssetProcessingService
import org.openremote.manager.asset.AssetStorageService
import org.openremote.manager.notification.PushNotificationHandler
import org.openremote.manager.rules.RulesEngine
import org.openremote.manager.rules.RulesService
import org.openremote.manager.rules.RulesetDeployment
import org.openremote.manager.rules.RulesetStorageService
import org.openremote.manager.rules.geofence.ORConsoleGeofenceAssetAdapter
import org.openremote.manager.setup.SetupService
import org.openremote.manager.setup.builtin.KeycloakDemoSetup
import org.openremote.manager.setup.builtin.ManagerDemoSetup
import org.openremote.model.attribute.AttributeEvent
import org.openremote.model.calendar.CalendarEvent
import org.openremote.model.calendar.RecurrenceRule
import org.openremote.model.console.ConsoleProvider
import org.openremote.model.console.ConsoleRegistration
import org.openremote.model.console.ConsoleResource
import org.openremote.model.geo.GeoJSONPoint
import org.openremote.model.notification.AbstractNotificationMessage
import org.openremote.model.notification.Notification
import org.openremote.model.notification.NotificationSendResult
import org.openremote.model.rules.Ruleset
import org.openremote.model.rules.RulesetStatus
import org.openremote.model.rules.TenantRuleset
import org.openremote.model.value.ObjectValue
import org.openremote.test.ManagerContainerTrait
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import java.time.Instant
import java.time.temporal.ChronoUnit

import static java.util.concurrent.TimeUnit.*
import static org.openremote.manager.setup.builtin.ManagerDemoSetup.DEMO_RULE_STATES_CUSTOMER_A
import static org.openremote.model.Constants.KEYCLOAK_CLIENT_ID
import static org.openremote.model.attribute.AttributeType.LOCATION
import static org.openremote.model.value.Values.parse

class JsonRulesTest extends Specification implements ManagerContainerTrait {

    def "Turn all lights off when console exits the residence geofence"() {

        def notificationMessages = []
        def targetIds = []

        given: "a mock push notification handler"
        PushNotificationHandler mockPushNotificationHandler = Spy(PushNotificationHandler) {
            isValid() >> true

            sendMessage(_ as Long, _ as Notification.Source, _ as String, _ as Notification.TargetType, _ as String, _ as AbstractNotificationMessage) >> {
                id, source, sourceId, targetType, targetId, message ->
                    notificationMessages << message
                    targetIds << targetId
                    callRealMethod()
            }

            // Assume sent to FCM
            sendMessage(_ as Message) >> {
                message -> return NotificationSendResult.success()
            }
        }

        and: "the container environment is started with the mock handler"
        def conditions = new PollingConditions(timeout: 10, delay: 1)
        def serverPort = findEphemeralPort()
        def services = Lists.newArrayList(defaultServices())
        services.removeIf {it instanceof PushNotificationHandler}
        services.add(mockPushNotificationHandler)
        def container = startContainerWithPseudoClock(defaultConfig(serverPort), services)
        def managerDemoSetup = container.getService(SetupService.class).getTaskOfType(ManagerDemoSetup.class)
        def keycloakDemoSetup = container.getService(SetupService.class).getTaskOfType(KeycloakDemoSetup.class)
        def rulesService = container.getService(RulesService.class)
        def rulesetStorageService = container.getService(RulesetStorageService.class)
        def timerService = container.getService(TimerService.class)
        def assetStorageService = container.getService(AssetStorageService.class)
        def assetProcessingService = container.getService(AssetProcessingService.class)
        RulesEngine tenantAEngine

        and: "some rules"
        Ruleset ruleset = new TenantRuleset(
            keycloakDemoSetup.tenantA.realm,
            "Demo Apartment - All Lights Off",
            Ruleset.Lang.JSON,
            getClass().getResource("/org/openremote/test/rules/BasicJsonRules.json").text)
        ruleset = rulesetStorageService.merge(ruleset)

        expect: "the rule engines to become available and be running with asset states inserted"
        conditions.eventually {
            tenantAEngine = rulesService.tenantEngines.get(keycloakDemoSetup.tenantA.realm)
            assert tenantAEngine != null
            assert tenantAEngine.isRunning()
            assert tenantAEngine.assetStates.size() == DEMO_RULE_STATES_CUSTOMER_A
        }

        and: "the room lights in an apartment to be on"
        conditions.eventually {
            def livingroomAsset = assetStorageService.find(managerDemoSetup.apartment2LivingroomId, true)
            assert livingroomAsset.getAttribute("lightSwitch").get().valueAsBoolean.get()
            assert livingroomAsset.getAttribute("lightSwitchTriggerTimes").get().valueAsArray.get().length() == 2
            assert livingroomAsset.getAttribute("plantsWaterLevels").get().valueAsObject.get().getNumber("cactus").get() == 0.8
            def bathRoomAsset = assetStorageService.find(managerDemoSetup.apartment2BathroomId, true)
            assert bathRoomAsset.getAttribute("lightSwitch").get().valueAsBoolean.get()
        }

        when: "a user authenticates"
        def accessToken = authenticate(
                container,
                keycloakDemoSetup.tenantA.realm,
                KEYCLOAK_CLIENT_ID,
                "testuser3",
                "testuser3"
        ).token

        and: "a console is registered by that user"
        def authenticatedConsoleResource = getClientApiTarget(serverUri(serverPort), keycloakDemoSetup.tenantA.realm, accessToken).proxy(ConsoleResource.class)
        def consoleRegistration = new ConsoleRegistration(null,
                "Test Console",
                "1.0",
                "Android 7.0",
                new HashMap<String, ConsoleProvider>() {
                    {
                        put("geofence", new ConsoleProvider(
                                ORConsoleGeofenceAssetAdapter.NAME,
                                true,
                                false,
                                false,
                                false,
                                false,
                                null
                        ))
                        put("push", new ConsoleProvider(
                                "fcm",
                                true,
                                true,
                                true,
                                true,
                                false,
                                (ObjectValue) parse("{token: \"23123213ad2313b0897efd\"}").orElse(null)
                        ))
                    }
                },
                "",
                ["manager"] as String[])
        consoleRegistration = authenticatedConsoleResource.register(null, consoleRegistration)

        then: "the console should have been registered"
        assert consoleRegistration.id != null

        when: "the console location is set to the apartment"
        assetProcessingService.sendAttributeEvent(new AttributeEvent(consoleRegistration.id, LOCATION.attributeName, ManagerDemoSetup.SMART_BUILDING_LOCATION.toValue()), AttributeEvent.Source.CLIENT)

        then: "the consoles location should have been updated"
        conditions.eventually {
            def testUser3Console = assetStorageService.find(consoleRegistration.id, true)
            assert testUser3Console != null
            def assetLocation = testUser3Console.getAttribute(LOCATION).flatMap { it.value }.flatMap {
                GeoJSONPoint.fromValue(it)
            }.orElse(null)
            assert assetLocation != null
            assert assetLocation.x == ManagerDemoSetup.SMART_BUILDING_LOCATION.x
            assert assetLocation.y == ManagerDemoSetup.SMART_BUILDING_LOCATION.y
            assert assetLocation.z == ManagerDemoSetup.SMART_BUILDING_LOCATION.z
        }

        then: "the console location asset state should be in the rule engine"
        conditions.eventually {
            assert tenantAEngine.assetStates.find {
                it.id == consoleRegistration.id && it.value.flatMap { GeoJSONPoint.fromValue(it) }.map {
                    it.x == ManagerDemoSetup.SMART_BUILDING_LOCATION.x && it.y == ManagerDemoSetup.SMART_BUILDING_LOCATION.y ? it : null
                }.isPresent()
            } != null
        }

        when: "the console device moves outside the home geofence (as defined in the rule)"
        assetProcessingService.sendAttributeEvent(new AttributeEvent(consoleRegistration.id, LOCATION.attributeName, new GeoJSONPoint(0d, 0d).toValue()), AttributeEvent.Source.CLIENT)

        then: "the apartment lights should be switched off"
        conditions.eventually {
            def livingroomAsset = assetStorageService.find(managerDemoSetup.apartment2LivingroomId, true)
            assert !livingroomAsset.getAttribute("lightSwitch").get().valueAsBoolean.get()
            assert livingroomAsset.getAttribute("lightSwitchTriggerTimes").get().valueAsArray.get().length() == 3
            assert livingroomAsset.getAttribute("plantsWaterLevels").get().valueAsObject.get().getNumber("cactus").get() == 0.7
            def bathRoomAsset = assetStorageService.find(managerDemoSetup.apartment2BathroomId, true)
            assert !bathRoomAsset.getAttribute("lightSwitch").get().valueAsBoolean.get()
        }

        and: "a notification should have been sent to the console"
        conditions.eventually {
            assert notificationMessages.size() == 1
            assert targetIds[0] == consoleRegistration.id
        }

        and: "after a few seconds the rule should not have fired again"
        new PollingConditions(initialDelay: 3).eventually {
            assert notificationMessages.size() == 1
        }

        when: "the console device moves back inside the home geofence (as defined in the rule)"
        def lastFireTimestamp = tenantAEngine.lastFireTimestamp
        assetProcessingService.sendAttributeEvent(new AttributeEvent(consoleRegistration.id, LOCATION.attributeName, ManagerDemoSetup.SMART_BUILDING_LOCATION.toValue()), AttributeEvent.Source.CLIENT)

        and: "the engine fires at least one more time"
        conditions.eventually {
            assert tenantAEngine.lastFireTimestamp > lastFireTimestamp
        }

        and: "the console device moves outside the home geofence again (as defined in the rule)"
        assetProcessingService.sendAttributeEvent(new AttributeEvent(consoleRegistration.id, LOCATION.attributeName, new GeoJSONPoint(0d, 0d).toValue()), AttributeEvent.Source.CLIENT)

        then: "another notification should have been sent to the console"
        conditions.eventually {
            assert notificationMessages.size() == 2
            assert targetIds[1] == consoleRegistration.id
        }

        when: "the console sends a location update with the same location but a newer timestamp"
        advancePseudoClock(35, MINUTES, container)
        assetProcessingService.sendAttributeEvent(new AttributeEvent(consoleRegistration.id, LOCATION.attributeName, new GeoJSONPoint(0d, 0d).toValue()), AttributeEvent.Source.CLIENT)

        then: "another notification should have been sent to the console (because the reset condition includes reset on timestampChanges)"
        conditions.eventually {
            assert notificationMessages.size() == 3
            assert targetIds[2] == consoleRegistration.id
        }

        when: "the console sends a location update with a new location but still outside the geofence"
        def timestamp = assetStorageService.find(consoleRegistration.id, true).getAttribute(LOCATION).flatMap{it.getValueTimestamp()}.orElse(timerService.getCurrentTimeMillis())
        def attributeEvent = new AttributeEvent(consoleRegistration.id, LOCATION.attributeName, new GeoJSONPoint(10d, 10d).toValue(), timestamp)
        assetProcessingService.sendAttributeEvent(attributeEvent, AttributeEvent.Source.CLIENT)

        then: "another notification should have been sent to the console (because the reset condition includes reset on valueChanges)"
        conditions.eventually {
            assert notificationMessages.size() == 4
            assert targetIds[3] == consoleRegistration.id
        }

        when: "when time advances 5 hours"
        advancePseudoClock(5, HOURS, container)

        and: "the same AttributeEvent is sent"
        assetProcessingService.sendAttributeEvent(attributeEvent, AttributeEvent.Source.CLIENT)

        then: "another notification should have been sent to the console (because the reset condition includes reset on timer)"
        conditions.eventually {
            assert notificationMessages.size() == 5
            assert targetIds[4] == consoleRegistration.id
        }

        when: "the Rules PAUSE_SCHEDULER is overridden to facilitate testing"
        RulesEngine engine
        RulesetDeployment deployment
        def originalPause = RulesEngine.PAUSE_SCHEDULER
        RulesEngine.PAUSE_SCHEDULER = {e, d ->
            engine = e
            deployment = d
            long delay = d.getValidTo() - timerService.getCurrentTimeMillis()
            // Don't actually schedule just simulate time passing and let test decide when to move on
            advancePseudoClock(delay, MILLISECONDS, container)

        }
        def originalUnpause = RulesEngine.UNPAUSE_SCHEDULER
        RulesEngine.UNPAUSE_SCHEDULER = {e, d ->
            engine = e
            deployment = d
            long delay = d.getValidFrom() - timerService.getCurrentTimeMillis()
            // Don't actually schedule just simulate time passing and let test decide when to move on
            advancePseudoClock(delay, MILLISECONDS, container)
        }

        and: "a validity period is added to the ruleset (fictional times to ensure firing in sensible time within test)"
        def version = ruleset.version
        def validityStart = Instant.ofEpochMilli(getClockTimeOf(container)).plus(2000, ChronoUnit.MILLIS)
        def validityEnd = Instant.ofEpochMilli(getClockTimeOf(container)).plus(4000, ChronoUnit.MILLIS)
        def calendarEvent = new CalendarEvent(
            Date.from(validityStart),
            Date.from(validityEnd),
            new RecurrenceRule(RecurrenceRule.Frequency.DAILY, 2, 3, null))
        ruleset = rulesetStorageService.merge(ruleset.addMeta(Ruleset.META_KEY_VALIDITY,calendarEvent.toValue()))

        then: "the ruleset should be redeployed and paused until 1st occurrence"
        conditions.eventually {
            assert tenantAEngine.deployments.find{it.key == ruleset.id}.value.version == version+1
            assert tenantAEngine.deployments.find{it.key == ruleset.id}.value.status == RulesetStatus.PAUSED
        }

        when: "the same AttributeEvent is sent"
        timestamp = attributeEvent.getTimestamp()+1
        attributeEvent.setTimestamp(timestamp)
        assetProcessingService.sendAttributeEvent(attributeEvent, AttributeEvent.Source.CLIENT)

        then: "the event should have been committed to the DB"
        conditions.eventually {
            def console = assetStorageService.find(consoleRegistration.id, true)
            assert console.getAttribute(LOCATION).flatMap{it.getValueTimestamp()}.orElse(0) == timestamp
        }

        and: "no notification should have been sent as outside the validity period"
        assert notificationMessages.size() == 5

        when: "the pause elapses"
        engine.unPauseRuleset(deployment)

        then: "eventually the ruleset should be unpaused (1st occurrence)"
        conditions.eventually {
            assert tenantAEngine.deployments.find{it.key == ruleset.id}.value.status == RulesetStatus.DEPLOYED
        }

        when: "the same AttributeEvent is sent"
        timestamp = attributeEvent.getTimestamp()+1
        attributeEvent.setTimestamp(timestamp)
        assetProcessingService.sendAttributeEvent(attributeEvent, AttributeEvent.Source.CLIENT)

        then: "the event should have been committed to the DB"
        conditions.eventually {
            def console = assetStorageService.find(consoleRegistration.id, true)
            assert console.getAttribute(LOCATION).flatMap{it.getValueTimestamp()}.orElse(0) == timestamp
        }

        and: "another notification should have been sent as inside the validity period"
        conditions.eventually {
            assert notificationMessages.size() == 6
            assert targetIds[5] == consoleRegistration.id
        }

        when: "the un-pause elapses"
        engine.pauseRuleset(deployment)

        then: "the ruleset should become paused again (until next occurrence)"
        conditions.eventually {
            assert tenantAEngine.deployments.find{it.key == ruleset.id}.value.status == RulesetStatus.PAUSED
        }

        when: "the same AttributeEvent is sent"
        timestamp = attributeEvent.getTimestamp()+1
        attributeEvent.setTimestamp(timestamp)
        assetProcessingService.sendAttributeEvent(attributeEvent, AttributeEvent.Source.CLIENT)

        then: "the event should have been committed to the DB"
        conditions.eventually {
            def console = assetStorageService.find(consoleRegistration.id, true)
            assert console.getAttribute(LOCATION).flatMap{it.getValueTimestamp()}.orElse(0) == timestamp
        }

        and: "no notification should have been sent as outside the validity period"
        assert notificationMessages.size() == 6

        when: "the pause elapses"
        engine.unPauseRuleset(deployment)

        then: "eventually the ruleset should be unpaused (next occurrence)"
        conditions.eventually {
            assert tenantAEngine.deployments.find{it.key == ruleset.id}.value.status == RulesetStatus.DEPLOYED
        }

        when: "the un-pause elapses"
        engine.pauseRuleset(deployment)

        then: "the ruleset should become paused again (until last occurrence)"
        conditions.eventually {
            assert tenantAEngine.deployments.find{it.key == ruleset.id}.value.status == RulesetStatus.PAUSED
        }

        when: "the pause elapses"
        engine.unPauseRuleset(deployment)

        then: "eventually the ruleset should be unpaused (last occurrence)"
        conditions.eventually {
            assert tenantAEngine.deployments.find{it.key == ruleset.id}.value.status == RulesetStatus.DEPLOYED
        }

        when: "the un-pause elapses"
        engine.pauseRuleset(deployment)

        then: "the ruleset should expire"
        conditions.eventually {
            assert tenantAEngine.deployments.find{it.key == ruleset.id}.value.status == RulesetStatus.EXPIRED
        }

        cleanup: "stop the container"
        RulesEngine.PAUSE_SCHEDULER = originalPause
        RulesEngine.UNPAUSE_SCHEDULER = originalUnpause
        stopContainer(container)
    }
}
