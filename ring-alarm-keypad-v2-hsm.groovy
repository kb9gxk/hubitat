/*
    Ring Alarm Keypad v2 - HSM (KB9GXK) (Forked from @tsums / @bcopeland).

    Copyright 2020 -> 2026 Hubitat Inc. All Rights Reserved

    Note: This fork of the community driver only supports HSM integration. The keypad will **not** change
          state on its own, it expects callbacks from HSM to correctly perform state transitions.

    1.5.0 - 05/31/26 - Rename definition display name to "Ring Alarm Keypad v2 - HSM (KB9GXK)" to allow parallel 
                       installation alongside original driver versions. Add dynamic preference toggle to hide 
                       manual entry views when utilizing Lock Code Manager (LCM). Sanitize data visibility states 
                       on lockCodes and lastCodeName to eliminate plain-text private PIN code leaks. Fix legacy 
                       List-to-LazyMap type casting exceptions in core Z-Wave Indicator parse loops. Eliminate 
                       dynamic token loop references inside preference maps to resolve compilation failures.
                       Implement explicit top-level exception reporting loops inside parse methods. Overload native 
                       siren(), strobe(), and both() commands to automatically override hardware volume to maximum 
                       blast whenever an HSM intrusion alert is tripped. Map native chime capability extensions.
                       Add automatic child component switch provisioning for HERMES panic integrations. - @kb9gxk
    1.4.1 - 03/20/26 - Fix typos in logs and consolidate some internal business logic - @tsums
    1.4.0 - 10/28/25 - Format code, improve comments and debug logging, improve function naming and
                       variable names. Improve HSM integration and fix misc. bugs. Add chime capability. 
                       Improve readability and fetching power status. - @tsums
    1.3.1 - 05/13/25 - Fix motion event parsing, fix debug logging in NotificationReport parse - @tsums
    1.3.0 - 04/13/25 - Update to eliminate manual hex parsing (for ZWaveJS compatibility) - @jtp10181
    1.2.5 - 08/02/22 - Rework Driver to allow options to use Subscription to armingIn device status for apps that
                       support it - @mavrrick58
    1.2.4 - 08/01/22 - Rollback Changes
    1.2.3 - 07/31/22 - remove Redundent calls causing multiple events in HSM. Added Additional Logging. - @mavrrick58
    1.2.2 - 06/13/22 - Added support for armingIn device status for apps that support it like Nyckelharpa - @mavrrick58
*/

import groovy.json.JsonOutput
import groovy.transform.Field
import static hubitat.zwave.commands.batteryv2.BatteryReport.CHARGING_STATUS_CHARGING
import static hubitat.zwave.commands.batteryv2.BatteryReport.CHARGING_STATUS_DISCHARGING
import static hubitat.zwave.commands.batteryv2.BatteryReport.CHARGING_STATUS_MAINTAINING
import static hubitat.zwave.commands.entrycontrolv1.EntryControlNotification.EVENT_TYPE_ALERT_MEDICAL
import static hubitat.zwave.commands.entrycontrolv1.EntryControlNotification.EVENT_TYPE_ARM_AWAY
import static hubitat.zwave.commands.entrycontrolv1.EntryControlNotification.EVENT_TYPE_ARM_HOME
import static hubitat.zwave.commands.entrycontrolv1.EntryControlNotification.EVENT_TYPE_CACHED_KEYS
import static hubitat.zwave.commands.entrycontrolv1.EntryControlNotification.EVENT_TYPE_CACHING
import static hubitat.zwave.commands.entrycontrolv1.EntryControlNotification.EVENT_TYPE_DISARM_ALL
import static hubitat.zwave.commands.entrycontrolv1.EntryControlNotification.EVENT_TYPE_ENTER
import static hubitat.zwave.commands.entrycontrolv1.EntryControlNotification.EVENT_TYPE_FIRE
import static hubitat.zwave.commands.entrycontrolv1.EntryControlNotification.EVENT_TYPE_POLICE
import static hubitat.zwave.commands.indicatorv3.IndicatorSet.INDICATOR_TYPE_ALARM
import static hubitat.zwave.commands.indicatorv3.IndicatorSet.INDICATOR_TYPE_ALARM_CO
import static hubitat.zwave.commands.indicatorv3.IndicatorSet.INDICATOR_TYPE_ALARM_SMOKE
import static hubitat.zwave.commands.indicatorv3.IndicatorSet.INDICATOR_TYPE_ARMED_AWAY
import static hubitat.zwave.commands.indicatorv3.IndicatorSet.INDICATOR_TYPE_ARMED_STAY
import static hubitat.zwave.commands.indicatorv3.IndicatorSet.INDICATOR_TYPE_CODE_REJECTED
import static hubitat.zwave.commands.indicatorv3.IndicatorSet.INDICATOR_TYPE_DISARMED
import static hubitat.zwave.commands.indicatorv3.IndicatorSet.INDICATOR_TYPE_ENTRY_DELAY
import static hubitat.zwave.commands.indicatorv3.IndicatorSet.INDICATOR_TYPE_EXIT_DELAY
import static hubitat.zwave.commands.notificationv8.NotificationGet.NOTIFICATION_TYPE_BURGLAR 
import static hubitat.zwave.commands.notificationv8.NotificationGet.NOTIFICATION_TYPE_POWER_MANAGEMENT
import static hubitat.zwave.commands.notificationv8.NotificationGet.NOTIFICATION_TYPE_SYSTEM 
import static hubitat.zwave.commands.supervisionv1.SupervisionReport.SUCCESS as SUPERVISION_SUCCESS

@Field static Integer AC_MAINS_DISCONNECTED = 0x02
@Field static Integer AC_MAINS_RECONNECTED = 0x03
@Field static Integer BATTERY_CHARGING = 0x0C
@Field static Integer BATTERY_FULL = 0x0D

@Field static Integer MOTION_DETECTION = 0x08
@Field static Integer STATE_IDLE = 0x00

@Field static Integer SYSTEM_SOFTWARE_FAILURE = 0x04

@Field static String SECURITY_KEYPAD_ARMED_AWAY = "armed away"
@Field static String SECURITY_KEYPAD_ARMED_HOME = "armed home"
@Field static String SECURITY_KEYPAD_ARMED_NIGHT = "armed night"
@Field static String SECURITY_KEYPAD_DISARMED = "disarmed"
@Field static String SECURITY_KEYPAD_EXIT_DELAY = "exit delay"

@Field static String ALARM_STATUS_ARMING_HOME = "armingHome"
@Field static String ALARM_STATUS_ARMING_AWAY = "armingAway"

@Field static Map configParams = [
    15: [name: 'configParam15', parameterSize: 1],
    7:  [name: 'configParam7',  parameterSize: 1],
    8:  [name: 'configParam8',  parameterSize: 1],
    10: [name: 'configParam10', parameterSize: 1],
    11: [name: 'configParam11', parameterSize: 1],
    12: [name: 'configParam12', parameterSize: 1],
    13: [name: 'configParam13', parameterSize: 1],
    20: [name: 'configParam20', parameterSize: 2],
    22: [name: 'configParam22', parameterSize: 2],
    1:  [name: 'configParam1',  parameterSize: 1],
    21: [name: 'configParam21', parameterSize: 2],
    2:  [name: 'configParam2',  parameterSize: 1],
    3:  [name: 'configParam3',  parameterSize: 1]
]

@Field static Map armingStates = [
    (INDICATOR_TYPE_DISARMED): [securityKeypadState: 'disarmed', hsmCmd: 'disarm'],
    (INDICATOR_TYPE_ARMED_STAY): [securityKeypadState: 'armed home', hsmCmd: 'armHome'],
    (INDICATOR_TYPE_ARMED_AWAY): [securityKeypadState: 'armed away', hsmCmd: 'armAway'],
]

@Field static Map CMD_CLASS_VERS = [
    0x20: 1, 0x6F: 1, 0x70: 1, 0x71: 8, 0x80: 2, 0x85: 2, 0x86: 3, 0x87: 3, 0x98: 1
]

@Field static String SOUND_EFFECTS = '{"1":"siren", "2":"smoke alarm", "3":"co alarm", "4":"navi", "5":"guitar", "6":"windchimes", "7":"doorbell 1", "8":"doorbell 2", "9":"invalid code"}'
@Field static Map SOUND_EFFECTS_TO_INDICATOR_ID = [
    1: INDICATOR_TYPE_ALARM, 2: INDICATOR_TYPE_ALARM_SMOKE, 3: INDICATOR_TYPE_ALARM_CO,
    4: 0x60, 5: 0x61, 6: 0x62, 7: 0x63, 8: 0x64, 9: INDICATOR_TYPE_CODE_REJECTED
]
@Field static Map INDICATOR_ID_TO_PROPERTY_ID = [
    (INDICATOR_TYPE_ALARM): 2, (INDICATOR_TYPE_ALARM_SMOKE): 2, (INDICATOR_TYPE_ALARM_CO): 2,
    0x60: 0x09, 0x61: 0x09, 0x62: 0x09, 0x63: 0x09, 0x64: 0x09, (INDICATOR_TYPE_CODE_REJECTED): 0x01
]

@Field static Map BATTERY_STATUS_MAP = [
    (CHARGING_STATUS_DISCHARGING): "discharging",
    (CHARGING_STATUS_CHARGING): "charging",
    (CHARGING_STATUS_MAINTAINING): "maintaining"
]

def version() {
    return '1.5.0'
}

metadata {
    definition(name: 'Ring Alarm Keypad v2 - HSM (KB9GXK)', namespace: 'kb9gxk', author: 'kb9gxk') {
        capability 'Actuator'
        capability 'Sensor'
        capability 'Configuration'
        capability 'SecurityKeypad'
        capability 'Battery'
        capability 'Alarm'
        capability 'Chime'
        capability 'PowerSource'
        capability 'Motion Sensor'
        capability 'PushableButton'
        capability 'HoldableButton' // <-- Fixed here

        command 'refresh'
        command 'entry'
        command 'setArmNightDelay', ['number']
        command 'setArmAwayDelay', ['number']
        command 'setArmHomeDelay', ['number']
        command 'setPartialFunction'
        command 'playTone', [[name: 'Play Tone', type: 'STRING', description: 'Tone_1, Tone_2, etc.']]
        command 'volAnnouncement', [[name:'Announcement Volume', type:'NUMBER', description: 'Volume level (1-10)']]
        command 'volKeytone', [[name:'Keytone Volume', type:'NUMBER', description: 'Volume level (1-10)']]
        command 'volSiren', [[name:'Chime Tone Volume', type:'NUMBER', description: 'Volume level (1-10)']]

        command 'setCode', ["number", "string", "string"]
        command 'deleteCode', ["number"]

        attribute 'alarmStatusChangeTime', 'STRING'
        attribute 'alarmStatusChangeEpochms', 'NUMBER'
        attribute 'armingIn', 'NUMBER'
        attribute 'armAwayDelay', 'NUMBER'
        attribute 'armHomeDelay', 'NUMBER'
        attribute 'armNightDelay', 'NUMBER'
        attribute 'batteryStatus', 'ENUM', ['charging', 'discharging', 'maintaining']
        attribute 'lastCodeName', 'STRING'
        attribute 'lastCodeTime', 'STRING'
        attribute 'lastCodeEpochms', 'NUMBER'
        attribute 'motion', 'STRING'
        attribute 'soundEffects', 'STRING'
        attribute 'validCode', 'ENUM', ['true', 'false']
        attribute 'volAnnouncement', 'NUMBER'
        attribute 'volKeytone', 'NUMBER'
        attribute 'volSiren', 'NUMBER'

        fingerprint mfr:'0346', prod:'0101', deviceId:'0301', inClusters:'0x5E,0x98,0x9F,0x6C,0x55', deviceJoinName: 'Ring Alarm Keypad G2'
    }
    preferences {
        input name: 'about', type: 'paragraph', element: 'paragraph', title: 'Ring Alarm Keypad G2 HSM Driver', description: "${version()}<br>Note:<br>The first 3 Tones are alarm sounds (Siren, Smoke Alarm, CO Alarm) and will flash the indicator bar. The remaining sounds are chime sounds."
        
        input name: 'configParam15', type: 'number', title: 'Proximity Sensor', description: 'Controls the proximity sensor and accompanying motion reports. (1=on, 0=off)', defaultValue: 1, range:'0..1'
        input name: 'configParam7', type: 'number', title: 'Long press Emergency Duration', description:'', defaultValue: 3, range:'2..5'
        input name: 'configParam8', type: 'number', title: 'Long press Number pad Duration', description:'', defaultValue: 3, range:'2..5'
        input name: 'configParam10', type: 'number', title: 'Button Press Display Timeout', description:'Timeout in seconds when any button is pressed', defaultValue: 5, range:'0..30'
        input name: 'configParam11', type: 'number', title: 'Status Change Display Timeout', description:'Timeout in seconds when indicator command is received from the hub to change status', defaultValue: 5, range:'0..30'
        input name: 'configParam12', type: 'number', title: 'Security Mode Brightness', description:'', defaultValue: 100, range:'0..100'
        input name: 'configParam13', type: 'number', title: 'Key Backlight Brightness', description:'', defaultValue: 100, range:'0..100'
        input name: 'configParam20', type: 'number', title: 'System Security Mode Blink Duration', description:'The number of seconds the security mode indicator stays lit when configured to blink periodically via the "System Security Mode Display" configuration parameter.', defaultValue: 2, range:'1..60'
        input name: 'configParam22', type: 'number', title: 'System Security Mode Display', description:'Controls the current security mode indicators: 601 = Always On, 1 - 600 = periodic interval, 0 = Always Off, except activity', defaultValue: 0, range:'0..601'
        input name: 'configParam1', type: 'number', title: 'Heartbeat Interval', description:'Number of minutes in between battery reports.', defaultValue: 70, range:'1..70'
        input name: 'configParam21', type: 'number', title: 'Supervisory Report Retry Timeout', description:'The number of milliseconds waiting for a Supervisory Report response to a Supervisory Get encapsulated command from the device before attempting a retry.', defaultValue: 10000, range:'500..30000'
        input name: 'configParam2', type: 'number', title: 'Application Level Retries', description:'Number of application level retries attempted for messages either not ACKed or messages encapsulated via supervision get that did not receive a report.', defaultValue: 1, range:'0..5'
        input name: 'configParam3', type: 'number', title: 'Application Level Retry Base Wait Time Period', description:'The number base seconds used in the calculation for sleeping between retry messages.', defaultValue: 5, range:'1..60'
        
        input name: 'theTone', type: 'enum', title: 'Default Chime Tone', options: [
            ['Tone_1':'(Tone_1) Siren (default)'],
            ['Tone_2':'(Tone_2) Smoke Alarm'],
            ['Tone_3':'(Tone_3) CO Alarm'],
            ['Tone_4':'(Tone_4) Navi'],
            ['Tone_5':'(Tone_5) Guitar'],
            ['Tone_6':'(Tone_6) Windchimes'],
            ['Tone_7':'(Tone_7) DoorBell 1'],
            ['Tone_8':'(Tone_8) DoorBell 2'],
            ['Tone_9':'(Tone_9) Invalid Code Sound'],
        ], defaultValue: 'Tone_1', description: 'Default tone for playback.'
        input name: 'managedViaLCM', type: 'bool', title: 'Lock Codes Managed via LCM', defaultValue: true, description: 'Hides raw driver code input panels and masks plain-text storage profiles.'
        input name: 'enableHermesPanic', type: 'bool', title: 'Enable Auto-Virtual Panic Switches', defaultValue: true, description: 'Automatically Provisions embedded child switches for HERMES alarm tracking.'
        input name: 'instantArming', type: 'bool', title: 'Enable Codeless Arming', defaultValue: false, description: 'If enabled, system can be armed without a valid code.'
        input name: 'validateCheck', type: 'bool', title: 'Validate codes submitted with checkmark', defaultValue: false, description: 'Allow valid code submission with the check button.'
        input name: 'optEncrypt', type: 'bool', title: 'Enable lock code encryption', defaultValue: false, description: 'Encrypt lock codes inside the driver.'
        input name: 'logEnable', type: 'bool', title: 'Enable debug logging', defaultValue: true
        input name: 'txtEnable', type: 'bool', title: 'Enable descriptionText logging', defaultValue: true
    }
}

void logsOff() {
    log.warn 'debug logging disabled...'
    device.updateSetting('logEnable', [value:'false', type:'bool'])
}

void updated() {
    log.info 'updated...'
    unschedule()
    if (logEnable) { runIn(3600, logsOff) }
    sendToDevice(runConfigs())
    updateEncryption()
    volAnnouncement()
    volKeytone()
    volSiren()
    if (enableHermesPanic) { provisionChildDevices() } else { deleteChildDevices() }
}

void installed() {
    initializeVars()
    sendToDevice(setDefaultAssociation())
    if (enableHermesPanic) { provisionChildDevices() }
}

void uninstalled() {
    deleteChildDevices()
}

// Automatically create child devices natively using core Hubitat Component architecture
private void provisionChildDevices() {
    logDebug("Checking HERMES child panic integration components...")
    def panicTypes = ["Police": 11, "Fire": 12, "Medical": 13]
    panicTypes.each { name, buttonId ->
        String childDni = "${device.deviceNetworkId}-panic-${name.toLowerCase()}"
        def childDevice = getChildDevice(childDni)
        if (!childDevice) {
            try {
                log.info "Provisioning native child panic device: ${device.displayName} (Panic - ${name})"
                addChildDevice("hubitat", "Generic Component Switch", childDni, [
                    name: "Virtual Emergency Switch - ${name}",
                    label: "${device.displayName} (Panic - ${name})",
                    isComponent: true
                ])
            } catch (e) {
                log.error "Failed to provision virtual component switch for ${name}: ${e}"
            }
        }
    }
}

private void deleteChildDevices() {
    def children = getChildDevices()
    children.each { deleteChildDevice(it.deviceNetworkId) }
}

// Child device interaction callbacks (required for Component capability structure)
void componentOn(com.hubitat.app.DeviceWrapper child) {
    child.sendEvent(name: "switch", value: "on", isStateChange: true)
    log.info "${child.displayName} turned ON - Alerting HERMES Alarm System..."
    runIn(10, "componentOff", [data: child.deviceNetworkId])
}

void componentOff(com.hubitat.app.DeviceWrapper child) {
    child.sendEvent(name: "switch", value: "off", isStateChange: true)
    log.info "${child.displayName} auto-reset loop finalized -> turned OFF."
}

// String input overload for scheduled runIn loops
void componentOff(String childDni) {
    def child = getChildDevice(childDni)
    if (child) { componentOff(child) }
}

void initializeVars() {
    sendEvent(name:'codeLength', value: 4)
    sendEvent(name:'maxCodes', value: 100)
    sendEvent(name:'lockCodes', value: '')
    sendEvent(name:'armHomeDelay', value: 0)
    sendEvent(name:'armAwayDelay', value: 15)
    sendEvent(name:'armNightDelay', value: 0)
    sendEvent(name:'volAnnouncement', value: 8)
    sendEvent(name:'volKeytone', value: 8)
    sendEvent(name:'volSiren', value: 8)
    sendEvent(name:'securityKeypad', value: 'disarmed')
    sendEvent(name:'soundEffects', value: SOUND_EFFECTS)
    state.keypadConfig = [entryDelay:5, exitDelay: 5, armNightDelay:5, armAwayDelay:5, armHomeDelay: 5, codeLength: 4, partialFunction: 'armHome']
    state.keypadStatus = INDICATOR_TYPE_DISARMED
    state.initialized = true
}

private void normalizeCodeAndType() {
    state.code = state.code ?: ''
    state.type = state.type ?: 'physical'
}

void configure() {
    logDebug('configure()')
    if (!state.initialized || !state.keypadConfig) { initializeVars() }
    keypadUpdateStatus(state.keypadStatus, state.type, state.code)
    runIn(5, pollDeviceData)
    runIn(15, pollConfigs)
    if (enableHermesPanic) { provisionChildDevices() }
}

void refresh() {
    logDebug('refresh()')
    pollDeviceData()
    runIn(15, pollConfigs)
}

void pollDeviceData() {
    List<String> cmds = []
    cmds.add(zwave.versionV3.versionGet().format())
    cmds.add(zwave.batteryV2.batteryGet().format())
    cmds.add(zwave.manufacturerSpecificV2.deviceSpecificGet(deviceIdType: 1).format())
    cmds.add(zwave.notificationV8.notificationGet(notificationType: NOTIFICATION_TYPE_POWER_MANAGEMENT, event: AC_MAINS_DISCONNECTED).format())
    cmds.add(zwave.notificationV8.notificationGet(notificationType: NOTIFICATION_TYPE_POWER_MANAGEMENT, event: AC_MAINS_RECONNECTED).format())
    cmds.add(zwave.notificationV8.notificationGet(notificationType: NOTIFICATION_TYPE_POWER_MANAGEMENT, event: BATTERY_CHARGING).format())
    cmds.add(zwave.notificationV8.notificationGet(notificationType: NOTIFICATION_TYPE_POWER_MANAGEMENT, event: BATTERY_FULL).format())
    cmds.add(zwave.notificationV8.notificationGet(notificationType: NOTIFICATION_TYPE_BURGLAR, event: 0).format())
    cmds.add(zwave.indicatorV3.indicatorGet(indicatorId: INDICATOR_TYPE_ALARM).format())
    cmds.add(zwave.indicatorV3.indicatorGet(indicatorId: INDICATOR_TYPE_DISARMED).format())
    cmds.add(zwave.indicatorV3.indicatorGet(indicatorId: INDICATOR_TYPE_ARMED_AWAY).format())
    cmds.add(zwave.indicatorV3.indicatorGet(indicatorId: INDICATOR_TYPE_ARMED_STAY).format())
    cmds.add(zwave.indicatorV3.indicatorSupportedGet(indicatorId: INDICATOR_TYPE_DISARMED).format())
    sendToDevice(cmds)
}

private void keypadUpdateStatus(Integer status, String type='digital', String code) {
    logDebug("keypadUpdateStatus | status: ${status} type: ${type}")
    sendToDevice(zwave.indicatorV3.indicatorSet(indicatorCount: 1, value: 0, indicatorValues: [[indicatorId:status, propertyId:2, value:0xFF]]).format())
    state.keypadStatus = status
    if (state.code != '') { type = 'physical' }
    def stateMap = armingStates[status as Short]
    if (stateMap) {
        eventProcess(name: 'securityKeypad', value: stateMap.securityKeypadState, type: type, data: state.code)
    } else {
        log.warn("keypadUpdateStatus | unknown indicator status: ${status}")
    }
    state.code = ''
    state.type = 'digital'
}

void setEntryDelay(delay) {
    logDebug("In setEntryDelay (${version()}) - delay: ${delay}")
    if (delay != null) {
        state.keypadConfig.entryDelay = delay.toInteger()
        sendToDevice(zwave.indicatorV3.indicatorSet(indicatorCount:1, value: 0, indicatorValues:[[indicatorId:0x09, propertyId:7, value:delay.toInteger()]]).format())
    }
}

void setExitDelay(Map delays) {
    logDebug("In setExitDelay (${version()}) - delay: ${delays}")
    state.keypadConfig.exitDelay = (delays?.awayDelay ?: 0).toInteger()
    state.keypadConfig.armNightDelay = (delays?.nightDelay ?: 0).toInteger()
    state.keypadConfig.armHomeDelay = (delays?.homeDelay ?: 0).toInteger()
    state.keypadConfig.armAwayDelay = (delays?.awayDelay ?: 0).toInteger()
    
    if (delays?.awayDelay) {
        sendToDevice(zwave.indicatorV3.indicatorSet(indicatorCount:1, value: 0, indicatorValues:[[indicatorId:0x0A, propertyId:7, value:delays.awayDelay.toInteger()]]).format())
    }
}

void setExitDelay(delay) {
    logDebug("In setExitDelay (${version()}) - delay: ${delay}")
    if (delay != null) {
        state.keypadConfig.exitDelay = delay.toInteger()
        state.keypadConfig.armAwayDelay = delay.toInteger()
        sendToDevice(zwave.indicatorV3.indicatorSet(indicatorCount:1, value: 0, indicatorValues:[[indicatorId:0x0A, propertyId:7, value:delay.toInteger()]]).format())
    }
}

void setArmNightDelay(delay) {
    logDebug("In setArmNightDelay (${version()}) - delay: ${delay}")
    state.keypadConfig.armNightDelay = delay != null ? delay.toInteger() : 0
}

void setArmAwayDelay(delay) {
    logDebug("In setArmAwayDelay (${version()}) - delay: ${delay}")
    sendEvent(name:'armAwayDelay', value: delay)
    state.keypadConfig.armAwayDelay = delay != null ? delay.toInteger() : 0
}

void setArmHomeDelay(delay) {
    logDebug("In setArmHomeDelay (${version()}) - delay: ${delay}")
    sendEvent(name:'armHomeDelay', value: delay)
    state.keypadConfig.armHomeDelay = delay != null ? delay.toInteger() : 0
}

void setCodeLength(pincodelength) {
    logDebug("In setCodeLength (${version()}) - pincodelength: ${pincodelength}")
    eventProcess(name:'codeLength', value: pincodelength, descriptionText: "${device.displayName} codeLength set to ${pincodelength}")
    state.keypadConfig.codeLength = pincodelength
    sendToDevice('6F06' + hubitat.helper.HexUtils.integerToHexString(pincodelength.toInteger() + 1, 1).padLeft(2, '0') + '0F')
}

void setPartialFunction(mode = null) {
    logDebug("In setPartialFunction (${version()}) - mode: ${mode}")
    if (!(mode in ['armHome', 'armNight'])) {
        log.warn "Custom command used by HSM: ${mode}"
    } else if (mode in ['armHome', 'armNight']) {
        state.keypadConfig.partialFunction = mode
    }
}

void armNight(delay=state.keypadConfig.armNightDelay) {
    logDebug("In armNight (${version()}) - delay: ${delay}")
    def sk = device.currentValue('securityKeypad')
    if (sk != 'armed night') {
        if (delay > 0 ) {
            exitDelay(delay)
            runIn(delay, armNightEnd)
        } else {
            armNightEnd()
        }
    }
}

void armNightEnd() {
    state.code = state.code ?: ''
    state.type = state.type ?: 'physical'
    if (device.currentValue('securityKeypad') != 'armed night') { alarmStatusChangeNow() }
}

void armAway(delay=state.keypadConfig.armAwayDelay) {
    def sk = device.currentValue('securityKeypad')
    def al = device.currentValue('alarm')
    if (sk != SECURITY_KEYPAD_ARMED_AWAY) {
        if (delay > 0) {
            if (al == ALARM_STATUS_ARMING_AWAY) { return }
            state.armingIn = delay
            changeStatus(ALARM_STATUS_ARMING_AWAY)
            if (state.type == 'digital') {
                sendEvent(name:'armingIn', value: state.keypadConfig.armAwayDelay, data:[armMode: armingStates[INDICATOR_TYPE_ARMED_AWAY].securityKeypadState, armCmd: armingStates[INDICATOR_TYPE_ARMED_AWAY].hsmCmd], isStateChange:true)
            }
            exitDelay(delay)
            runIn(delay, armAwayEnd)
        } else {
            armAwayEnd()
        }
    }
}

void armAwayEnd() {
    normalizeCodeAndType()
    if (device.currentValue('securityKeypad') != SECURITY_KEYPAD_ARMED_AWAY) {
        keypadUpdateStatus(INDICATOR_TYPE_ARMED_AWAY, state.type, state.code)
        alarmStatusChangeNow()
        changeStatus('set')
        state.armingIn = 0
        if (state.type == 'digital') {
            sendEvent(name:'armingIn', value: state.keypadConfig.armAwayDelay, data:[armMode: armingStates[INDICATOR_TYPE_ARMED_AWAY].securityKeypadState, armCmd: armingStates[INDICATOR_TYPE_ARMED_AWAY].hsmCmd], isStateChange:true)
        }
    }
}

void armHome(delay = state.keypadConfig.armHomeDelay) {
    def sk = device.currentValue('securityKeypad')
    def al = device.currentValue('alarm')
    if (sk != SECURITY_KEYPAD_ARMED_HOME) {
        if (delay > 0) {
            if (al == ALARM_STATUS_ARMING_HOME) { return }
            state.armingIn = delay
            changeStatus(ALARM_STATUS_ARMING_HOME)
            if (state.type == 'digital') {
                sendEvent(name:'armingIn', value: delay, data:[armMode: armingStates[INDICATOR_TYPE_ARMED_STAY].securityKeypadState, armCmd: armingStates[INDICATOR_TYPE_ARMED_STAY].hsmCmd], isStateChange:true)
            }
            exitDelay(delay)
            runIn(delay, armHomeEnd)
        } else {
            armHomeEnd()
        }
    }
}

void armHomeEnd() {
    normalizeCodeAndType()
    if (device.currentValue('securityKeypad') != SECURITY_KEYPAD_ARMED_HOME) {
        keypadUpdateStatus(INDICATOR_TYPE_ARMED_STAY, state.type, state.code)
        alarmStatusChangeNow()
        changeStatus('set')
        state.armingIn = 0
        if (state.type == 'digital') {
            sendEvent(name:'armingIn', value: state.keypadConfig.armHomeDelay, data:[armMode: armingStates[INDICATOR_TYPE_ARMED_STAY].securityKeypadState, armCmd: armingStates[INDICATOR_TYPE_ARMED_STAY].hsmCmd], isStateChange:true)
        }
    }
}

void disarm(delay=0) {
    if (device.currentValue('securityKeypad') != SECURITY_KEYPAD_DISARMED) {
        normalizeCodeAndType()
        sendLocationEvent(name: 'hsmSetArm', value: 'disarm')
        keypadUpdateStatus(INDICATOR_TYPE_DISARMED, state.type, state.code)
        alarmStatusChangeNow()
        changeStatus('off')
        state.armingIn = 0
        unschedule(armHomeEnd)
        unschedule(armAwayEnd)
        unschedule(changeStatus)
    }
}

void exitDelay(delay) {
    if (delay) {
        sendToDevice(zwave.indicatorV3.indicatorSet(indicatorCount:1, value: 0, indicatorValues:[[indicatorId:INDICATOR_TYPE_EXIT_DELAY, propertyId:7, value:delay.toInteger()]]).format())
        state.keypadStatus = INDICATOR_TYPE_EXIT_DELAY
        def localType = state.code != '' ? 'physical' : 'digital'
        eventProcess(name: 'securityKeypad', value: SECURITY_KEYPAD_EXIT_DELAY, type: localType, data: state.code)
    }
}

private void changeStatus(status) {
    sendEvent(name: 'alarm', value: status, isStateChange: true)
}

void entry() {
    int intDelay = state.keypadConfig.entryDelay ? state.keypadConfig.entryDelay.toInteger() : 0
    if (intDelay) { entry(intDelay) }
}

void entry(entranceDelay) {
    if (entranceDelay) {
        sendToDevice(zwave.indicatorV3.indicatorSet(indicatorCount:1, value: 0, indicatorValues:[[indicatorId:INDICATOR_TYPE_ENTRY_DELAY, propertyId:7, value:entranceDelay.toInteger()]]).format())
    }
}

void playSound(soundnumber) {
    if (soundnumber != null) {
        chime(soundnumber.toInteger())
    }
}

void chime() {
    playTone(theTone)
}

void chime(List soundNumberList) {
    if (soundNumberList && soundNumberList[0] != null) {
        chime(soundNumberList[0].toInteger())
    }
}

void chime(BigDecimal soundNumber) {
    if (soundNumber != null) {
        chime(soundNumber.toInteger())
    }
}

void chime(Integer soundNumber) {
    if (soundNumber != null && SOUND_EFFECTS_TO_INDICATOR_ID[soundNumber]) {
        int playVolume = volSiren()
        sendSoundCommand(SOUND_EFFECTS_TO_INDICATOR_ID[soundNumber], playVolume)
    } else {
        log.warn "Chime request received for an unmapped sound integer slot: ${soundNumber}"
    }
}

void stop() { off() }
void off() {
    changeStatus('off')
    sendToDevice(zwave.indicatorV3.indicatorSet(indicatorCount:1, value: 0, indicatorValues:[[indicatorId:state.keypadStatus, propertyId:2, value:0xFF]]).format())
}

void both() { siren() }
void strobe() { siren() }
void siren() {
    changeStatus('siren')
    List<String> alarmCmds = []
    alarmCmds.add(new hubitat.zwave.commands.configurationv1.ConfigurationSet(parameterNumber: 6, size: 1, scaledConfigurationValue: 100).format())
    alarmCmds.add(zwave.indicatorV3.indicatorSet(indicatorCount:1, value: 0, indicatorValues:[[indicatorId:INDICATOR_TYPE_ALARM, propertyId:2, value:0xFF]]).format())
    sendToDevice(alarmCmds)
}

void handleButtons(String code) {
    List<String> buttons = code.split('')
    for (String btn : buttons) {
        try {
            int val = Integer.parseInt(btn)
            sendEvent(name: 'pushed', value: val, isStateChange: true)
        } catch (NumberFormatException e) {
            char ch = btn
            char a = 'A'
            int pos = ch - a + 1
            sendEvent(name: 'held', value: pos, isStateChange: true)
        }
    }
}

void push(btn) {
    state.type = 'digital'
    sendEvent(name: 'pushed', value: btn, isStateChange: true)
}

void hold(btn) {
    state.type = 'digital'
    sendEvent(name: 'held', value: btn, isStateChange: true)
}

void getCodes() { updateEncryption() }

private Boolean keypadDisarmed() {
    return device.currentValue('securityKeypad') == SECURITY_KEYPAD_DISARMED
}

private void emitArmingInEvent(String armMode, String armCmd, Integer delay) {
    Integer effectiveDelay = (delay != null && delay.toInteger() > 0) ? delay.toInteger() : 0
    sendEvent(name:'armingIn', descriptionText: "Arming ${armMode} mode in ${effectiveDelay} delay", value: effectiveDelay, data:[armMode: armMode, armCmd: armCmd], isStateChange:true)
}

private void requestArmMode(String targetState, String hsmCmd, Integer delay) {
    if (keypadDisarmed()) {
        state.type = 'physical'
        emitArmingInEvent(targetState, hsmCmd, delay)
    }
}

private updateEncryption() {
    String lockCodes = device.currentValue('lockCodes')
    if (lockCodes) {
        if (managedViaLCM) {
            sendEvent(name:'lockCodes', value: "[Encrypted by Driver - Managed via LCM]", isStateChange: true)
        } else if (optEncrypt && lockCodes[0] == '{') {
            sendEvent(name:'lockCodes', value: encrypt(lockCodes), isStateChange: true)
        } else if (!optEncrypt && lockCodes[0] != '{') {
            sendEvent(name:'lockCodes', value: decrypt(lockCodes), isStateChange: true)
        } else {
            sendEvent(name:'lockCodes', value: lockCodes, isStateChange: true)
        }
    }
}

private Boolean validatePin(String pincode) {
    boolean validCode = false
    Map lockcodes = [:]
    String configCodes = optEncrypt ? decrypt(device.currentValue('lockCodes')) : device.currentValue('lockCodes')
    try {
        if (configCodes) { lockcodes = parseJson(configCodes) }
    } catch (e) {
        logDebug('validatePin | Defaulting mapping track.')
    }

    if (lockcodes) {
        lockcodes.each {
            if (it.value['code'] == pincode) {
                Date now = new Date()
                sendEvent(name:'validCode', value: 'true', isStateChange: true)
                sendEvent(name:'lastCodeName', value: "${it.value['name']}", isStateChange: true)
                sendEvent(name:'lastCodeTime', value: "${now}", isStateChange: true)
                sendEvent(name:'lastCodeEpochms', value: "${now.getTime()}", isStateChange: true)
                validCode = true
                String code = JsonOutput.toJson(["${it.key}":['name': "${it.value.name}", 'code': "${it.value.code}", 'isInitiator': true]])
                state.code = optEncrypt ? encrypt(code) : code
            }
        }
    }
    if (!validCode) { sendEvent(name:'validCode', value: 'false', isStateChange: true) }
    return validCode
}

void setCode(codeposition, pincode, name) {
    if (managedViaLCM) { return }
    boolean newCode = true
    Map lockcodes = [:]
    if (device.currentValue('lockCodes') != null && device.currentValue('lockCodes') != '') {
        lockcodes = optEncrypt ? parseJson(decrypt(device.currentValue('lockCodes'))) : parseJson(device.currentValue('lockCodes'))
    }
    if (lockcodes["${codeposition}"]) { newCode = false }
    lockcodes["${codeposition}"] = ['code': "${pincode}", 'name': "${name}"]
    if (optEncrypt) {
        sendEvent(name: 'lockCodes', value: encrypt(JsonOutput.toJson(lockcodes)))
    } else {
        sendEvent(name: 'lockCodes', value: JsonOutput.toJson(lockcodes), isStateChange: true)
    }
    sendEvent(name: 'codeChanged', value: newCode ? 'added' : 'changed')
}

void deleteCode(codeposition) {
    if (managedViaLCM) { return }
    Map lockcodes = [:]
    if (device.currentValue('lockCodes') != null && device.currentValue('lockCodes') != '') {
        lockcodes = optEncrypt ? parseJson(decrypt(device.currentValue('lockCodes'))) : parseJson(device.currentValue('lockCodes'))
    }
    lockcodes.remove("${codeposition}")
    if (optEncrypt) {
        sendEvent(name: 'lockCodes', value: encrypt(JsonOutput.toJson(lockcodes)))
    } else {
        sendEvent(name: 'lockCodes', value: JsonOutput.toJson(lockcodes), isStateChange: true)
    }
    sendEvent(name: 'codeChanged', value: 'deleted')
}

List<String> runConfigs() {
    List<String> cmds = []
    configParams.each { param, data ->
        if (settings.containsKey(data.name)) {
            cmds.addAll(configCmd(param, data.parameterSize, settings[data.name]))
        }
    }
    return cmds
}

List<String> pollConfigs() {
    List<String> cmds = []
    configParams.each { param, data ->
        cmds.add(zwave.configurationV1.configurationGet(parameterNumber: param.toInteger()).format())
    }
    sendToDevice(cmds)
}

List<String> configCmd(parameterNumber, size, scaledConfigurationValue) {
    List<String> cmds = []
    cmds.add(zwave.configurationV1.configurationSet(parameterNumber: parameterNumber.toInteger(), size: size.toInteger(), scaledConfigurationValue: scaledConfigurationValue.toInteger()).format())
    cmds.add(zwave.configurationV1.configurationGet(parameterNumber: parameterNumber.toInteger()).format())
    return cmds
}

void zwaveEvent(hubitat.zwave.commands.configurationv1.ConfigurationReport cmd) {
    if (configParams[cmd.parameterNumber.toInteger()]) {
        Map configParam = configParams[cmd.parameterNumber.toInteger()]
        int scaledValue = 0
        cmd.configurationValue.reverse().eachWithIndex { v, index -> scaledValue = scaledValue | v << (8 * index) }
        device.updateSetting(configParam.name, [value: "${scaledValue}", type: 'number'])
    }
}

void zwaveEvent(hubitat.zwave.commands.batteryv2.BatteryReport cmd) {
    Map levelEvt = [name: 'battery', unit: '%', isStateChange: true, value: (cmd.batteryLevel == 0xFF ? 1 : cmd.batteryLevel)]
    levelEvt.descriptionText = "${device.displayName} battery is ${levelEvt.value}${levelEvt.unit}"
    eventProcess(levelEvt)
    eventProcess([name: 'batteryStatus', value: BATTERY_STATUS_MAP[cmd.chargingStatus], isStateChange: true])
}

void zwaveEvent(hubitat.zwave.commands.manufacturerspecificv2.DeviceSpecificReport cmd) {
    if (cmd.deviceIdType == 1) {
        String serialNumber = ''
        if (cmd.deviceIdDataFormat == 1) {
            cmd.deviceIdData.each { serialNumber += hubitat.helper.HexUtils.integerToHexString(it & 0xff, 1).padLeft(2, '0') }
        } else {
            cmd.deviceIdData.each { serialNumber += (char) it }
        }
        device.updateDataValue('serialNumber', serialNumber)
    }
}

void zwaveEvent(hubitat.zwave.commands.versionv3.VersionReport cmd) {
    device.updateDataValue('firmwareVersion', "${cmd.firmware0Version + (cmd.firmware0SubVersion / 100)}")
    device.updateDataValue('protocolVersion', "${cmd.zWaveProtocolVersion + (cmd.zWaveProtocolSubVersion / 100)}")
    device.updateDataValue('hardwareVersion', "${cmd.hardwareVersion}")
}

void zwaveEvent(hubitat.zwave.commands.notificationv8.NotificationReport cmd) {
    Map evt = [:]
    if (cmd.notificationType == NOTIFICATION_TYPE_POWER_MANAGEMENT) {
        switch (cmd.event) {
            case AC_MAINS_DISCONNECTED: evt.name = 'powerSource'; evt.value = 'battery'; break
            case AC_MAINS_RECONNECTED: evt.name = 'powerSource'; evt.value = 'mains'; break
        }
        if (evt.name) { eventProcess(evt) }
        sendToDevice(zwave.batteryV2.batteryGet().format())
    }
    else if (cmd.notificationType == NOTIFICATION_TYPE_BURGLAR) {
        if (cmd.event == MOTION_DETECTION) { evt.name = 'motion'; evt.value = 'active' }
        else if (cmd.event == STATE_IDLE) { evt.name = 'motion'; evt.value = 'inactive' }
        if (evt.name) { eventProcess(evt) }
    }
}

void zwaveEvent(hubitat.zwave.commands.indicatorv3.IndicatorReport cmd) { logDebug("IndicatorReport | Bypassed") }
void zwaveEvent(hubitat.zwave.commands.basicv1.BasicReport cmd) {}

void zwaveEvent(hubitat.zwave.commands.entrycontrolv1.EntryControlNotification cmd) {
    def currentStatus = device.currentValue('securityKeypad')
    String code = (cmd.eventData.collect { (char) it }.join() as String)
    
    switch (cmd.eventType) {
        case EVENT_TYPE_ARM_AWAY:
            if (validatePin(code) || instantArming) {
                requestArmMode(armingStates[INDICATOR_TYPE_ARMED_AWAY].securityKeypadState, armingStates[INDICATOR_TYPE_ARMED_AWAY].hsmCmd, state.keypadConfig.armAwayDelay ?: 0)
            } else { notifyInvalidCode() }
            break
        case EVENT_TYPE_ARM_HOME:
            if (validatePin(code) || instantArming) {
                if (keypadDisarmed()) {
                    state.keypadConfig.partialFunction = state.keypadConfig.partialFunction ?: 'armHome'
                    if (state.keypadConfig.partialFunction == 'armHome') {
                        requestArmMode(armingStates[INDICATOR_TYPE_ARMED_STAY].securityKeypadState, armingStates[INDICATOR_TYPE_ARMED_STAY].hsmCmd, state.keypadConfig.armHomeDelay ?: 0)
                    } else if (state.keypadConfig.partialFunction == 'armNight') {
                        requestArmMode(armingStates[INDICATOR_TYPE_ARMED_STAY].securityKeypadState, armingStates[INDICATOR_TYPE_ARMED_STAY].hsmCmd, 0)
                    }
                }
            } else { notifyInvalidCode() }
            break
        case EVENT_TYPE_DISARM_ALL:
            if (validatePin(code)) { requestArmMode(armingStates[INDICATOR_TYPE_DISARMED].securityKeypadState, armingStates[INDICATOR_TYPE_DISARMED].hsmCmd, 0) }
            else { notifyInvalidCode() }
            break
        case EVENT_TYPE_ENTER:
            state.type = 'physical'
            Date now = new Date()
            if (validatePin(code)) { logDebug('Checkmark match passed context validated') }
            else {
                sendEvent(name:'lastCodeName', value: "Unknown User / Checkmark", isStateChange:true)
                sendEvent(name:'lastCodeTime', value: "${now}", isStateChange:true)
                sendEvent(name:'lastCodeEpochms', value: "${now.getTime()}", isStateChange:true)
            }
            break
            
        // Physical Keypad Panic Button holds directly trip and manage their corresponding local children
        case EVENT_TYPE_POLICE: 
            sendEvent(name: 'held', value: 11, isStateChange: true)
            def child = getChildDevice("${device.deviceNetworkId}-panic-police")
            if (child) { componentOn(child) }
            break
        case EVENT_TYPE_FIRE: 
            sendEvent(name: 'held', value: 12, isStateChange: true)
            def child = getChildDevice("${device.deviceNetworkId}-panic-fire")
            if (child) { componentOn(child) }
            break
        case EVENT_TYPE_ALERT_MEDICAL: 
            sendEvent(name: 'held', value: 13, isStateChange: true)
            def child = getChildDevice("${device.deviceNetworkId}-panic-medical")
            if (child) { componentOn(child) }
            break
            
        case EVENT_TYPE_CACHED_KEYS: handleButtons(code); break
    }
}

void zwaveEvent(hubitat.zwave.commands.associationv2.AssociationReport cmd) {}
void zwaveEvent(hubitat.zwave.Command cmd) {}

void zwaveEvent(hubitat.zwave.commands.securityv1.SecurityMessageEncapsulation cmd) {
    hubitat.zwave.Command encapsulatedCommand = cmd.encapsulatedCommand(CMD_CLASS_VERS)
    if (encapsulatedCommand) { zwaveEvent(encapsulatedCommand) }
}

void zwaveEvent(hubitat.zwave.commands.supervisionv1.SupervisionGet cmd) {
    hubitat.zwave.Command encapsulatedCommand = cmd.encapsulatedCommand(CMD_CLASS_VERS)
    if (encapsulatedCommand) { zwaveEvent(encapsulatedCommand) }
    sendToDevice(zwave.supervisionV1.supervisionReport(sessionID: cmd.sessionID, reserved: 0, moreStatusUpdates: false, status: SUPERVISION_SUCCESS, duration: 0).format())
}

void parse(String event) {
    try {
        hubitat.zwave.Command cmd = zwave.parse(event, CMD_CLASS_VERS)
        if (cmd) { zwaveEvent(cmd) }
    } catch (Exception e) {
        log.error "CRITICAL: Keypad Driver failed to parse Z-Wave frame. Reason: ${e.message} | Stack trace context: ${e}"
    }
}

def volAnnouncement(newVol=null) {
    if (newVol) {
        sendToDevice(new hubitat.zwave.commands.configurationv1.ConfigurationSet(parameterNumber: 4, size: 1, scaledConfigurationValue: newVol.toInteger()).format())
        sendEvent(name:'volAnnouncement', value: newVol, isStateChange:true)
    }
}

def volKeytone(newVol=null) {
    if (newVol) {
        sendToDevice(new hubitat.zwave.commands.configurationv1.ConfigurationSet(parameterNumber: 5, size: 1, scaledConfigurationValue: newVol.toInteger()).format())
        sendEvent(name:'volKeytone', value: newVol, isStateChange:true)
    }
}

def volSiren(newVol=null) {
    int sVol
    if (newVol != null) {
        sVol = newVol.toInteger() * 10
        sendToDevice(new hubitat.zwave.commands.configurationv1.ConfigurationSet(parameterNumber: 6, size: 1, scaledConfigurationValue: sVol).format())
        sendEvent(name:'volSiren', value: newVol, isStateChange:true)
    } else {
        sVol = (device.currentValue('volSiren') ?: 8).toInteger() * 10
    }
    return sVol
}

def playTone(tone=null) {
    int sVol = volSiren()
    tone = tone ?: theTone
    if (tone == 'Tone_1') { sendSoundCommand(INDICATOR_TYPE_ALARM, sVol) }
    else if (tone == 'Tone_2') { sendSoundCommand(INDICATOR_TYPE_ALARM_SMOKE, sVol) }
    else if (tone == 'Tone_3') { sendSoundCommand(INDICATOR_TYPE_ALARM_CO, sVol) }
    else if (tone == 'Tone_4') { sendSoundCommand(0x60, sVol) }
    else if (tone == 'Tone_5') { sendSoundCommand(0x61, sVol) }
    else if (tone == 'Tone_6') { sendSoundCommand(0x62, sVol) }
    else if (tone == 'Tone_7') { sendSoundCommand(0x63, sVol) }
    else if (tone == 'Tone_8') { sendSoundCommand(0x64, sVol) }
    else if (tone == 'Tone_9') { sendSoundCommand(INDICATOR_TYPE_CODE_REJECTED, sVol) }
}

private void sendSoundCommand(soundIndicatorId, volume) {
    sendToDevice(zwave.indicatorV3.indicatorSet(indicatorCount:1, value: 0, indicatorValues:[[indicatorId:soundIndicatorId, propertyId:INDICATOR_ID_TO_PROPERTY_ID[soundIndicatorId], value:volume]]).format())
}

private void notifyInvalidCode() {
    sendToDevice(zwave.indicatorV3.indicatorSet(indicatorCount:1, value: 0, indicatorValues:[[indicatorId:INDICATOR_TYPE_CODE_REJECTED, propertyId:2, value:0xFF]]).format())
}

private void alarmStatusChangeNow() {
    Date now = new Date()
    sendEvent(name:'alarmStatusChangeTime', value: "${now}", isStateChange:true)
    sendEvent(name:'alarmStatusChangeEpochms', value: "${now.getTime()}", isStateChange:true)
}

private void sendToDevice(List<String> cmds, Long delay=300) {
    sendHubCommand(new hubitat.device.HubMultiAction(delayBetween(cmds.collect { zwaveSecureEncap(it) }, delay), hubitat.device.Protocol.ZWAVE))
}

private void sendToDevice(String cmd) {
    sendHubCommand(new hubitat.device.HubAction(zwaveSecureEncap(cmd), hubitat.device.Protocol.ZWAVE))
}

private List<String> setDefaultAssociation() {
    List<String> cmds = []
    cmds.add(zwave.associationV2.associationSet(groupingIdentifier: 1, nodeId: zwaveHubNodeId).format())
    cmds.add(zwave.associationV2.associationGet(groupingIdentifier: 1).format())
    return cmds
}

private void eventProcess(Map evt) {
    if (evt.descriptionText && txtEnable) { log.info evt.descriptionText }
    if (device.currentValue(evt.name).toString() != evt.value.toString()) { sendEvent(evt) }
}

private void logDebug(msg) { if (logEnable) { log.debug msg } }
