import groovy.json.*

/*
 * pp-Code H&T Sensor
 * Version: 1.0.0
 * Attribution: Initial base code from https://buy.watchman.online/HubitatDriverWatchman.pdf
 * Author: kb9gxk (Jeff Parrish)
 */

metadata {
    definition(
        name: 'pp-Code H&T Sensor', // Name of the device
        namespace: 'ppcode', // Namespace for the device
        author: 'kb9gxk (Jeff Parrish)', // Author's name
        importUrl: 'https://raw.githubusercontent.com/kb9gxk/hubitat/refs/heads/main/hubitat-ppcode.groovy'
    ) {
        capability 'TemperatureMeasurement' // Capability to measure temperature
        capability 'RelativeHumidityMeasurement' // Capability to measure humidity
        capability 'Sensor' // General sensor capability
        capability 'Refresh' // Capability to refresh the device state
        capability 'Polling' // Capability to poll the device
    }
    
    preferences {
        input('ip', 'string', title: 'IP', description: 'Sensor IP Address', defaultValue: '',
            required: false, displayDuringSetup: true) // Input for IP address
        input('port', 'string', title: 'PORT', description: 'Sensor TCP Port', defaultValue: '80',
            required: false, displayDuringSetup: true) // Input for TCP port
        input('deviceSerial', 'string', title: 'Serial', description: 'Device Serial Number',
            defaultValue: '', required: false, displayDuringSetup: true) // Input for device serial
        input 'tempFormat', 'enum', title: 'Temperature Format', required: false, defaultValue: false,
            options: [F: 'Fahrenheit', C: 'Celsius'] // Temperature format selection
        input 'refreshEvery', 'enum', title: 'Enable auto refresh every XX Minutes', required: false,
            defaultValue: false, options: [5: '5 minutes', 10: '10 minutes', 15: '15 minutes', 30: '30 minutes'] // Refresh interval
        input 'locale', 'enum', title: 'Choose refresh date format', required: true, defaultValue: true,
            options: [US: 'US MM/DD/YYYY', UK: 'UK DD/MM/YYYY'] // Date format selection
        input name: 'debugOutput', type: 'bool', title: 'Enable debug logging?', defaultValue: true // Debug logging option
        input name: 'txtEnable', type: 'bool', title: 'Enable descriptionText logging', defaultValue: true // Text logging option
    }
}

def installed() {
    log.debug 'Installed' // Log installation event
    refresh() // Initial refresh
}

def updated() {
    log.debug 'Updated' // Log update event
    log.info 'Preferences updated...' // Log preference update
    log.warn "Debug logging is: ${debugOutput == true}" // Log current debug state
    unschedule() // Unschedule any previous refresh tasks

    if (refreshEvery) { // Check if auto-refresh interval is set
        runEvery(refreshEvery.toInteger(), 'autorefresh') // Schedule auto-refresh based on user preference
        log.info "Refresh set for every ${refreshEvery} Minutes" // Log refresh setting
    } else {
        runEvery(30, 'autorefresh') // Default to every 30 minutes
        log.info 'Refresh set for every 30 Minutes' // Log default refresh setting
    }
    if (debugOutput) { runIn(1800, logsOff) } // Schedule debug logging to turn off after 30 minutes
    state.LastRefresh = new Date().format('YYYY/MM/dd \n HH:mm:ss', location.timeZone) // Update last refresh timestamp
    log.debug "Last refresh timestamp set to: ${state.LastRefresh}" // Log timestamp
    refresh() // Refresh device state
}


def parse(description) {
    logDebug "Parsing result: $description" // Log parsing initiation
    def msg = parseLanMessage(description) // Parse the LAN message

    log.debug "Headers: ${msg.headers}" // Log headers
    log.debug "Status: ${msg.status}" // Log status

    def body = msg.body // Extract body from message
    log.debug "Raw Body: $body" // Log raw body

    def decodedBody = body // Initialize decoded body
    def data // Initialize data variable
    try {
        data = new groovy.json.JsonSlurper().parseText(decodedBody) // Parse JSON data
        log.debug "Parsed Data: $data" // Log parsed data
    } catch (Exception e) {
        log.error "Failed to parse JSON: ${e.message} for body: ${decodedBody}" // Log error
        return // Exit if parsing fails
    }

    if (data?.Stats?.Temp && data?.Stats?.Humi) { // Check if Temp and Humi data exist
    String myTemp = data.Stats.Temp.replaceAll('[F]', '') // Remove 'F' from temperature
    String myHumi = data.Stats.Humi.replaceAll('[%]', '') // Remove '%' from humidity

    myTemptrimmed = myTemp.substring(0, myTemp.indexOf('.')) // Trim temperature string
    myHumitrimmed = myHumi.substring(0, myHumi.indexOf('.')) // Trim humidity string

    log.debug "Trimmed Temperature: $myTemptrimmed, Trimmed Humidity: $myHumitrimmed" // Log trimmed values

    int myTempint = Integer.parseInt(myTemptrimmed) // Parse trimmed temperature to integer
    String TempResult
    if (tempFormat == 'C') { // Check temperature format
        TempResult = ((myTempint - 32) * 5 / 9) + 'C' // Convert to Celsius without rounding
        log.debug "Converted Temperature to Celsius: $TempResult" // Log conversion
    } else {
        TempResult = myTempint + 'F' // Keep temperature in Fahrenheit
        log.debug "Temperature remains in Fahrenheit: $TempResult" // Log Fahrenheit
    }

    String HumiResult = myHumitrimmed + ' %' // Use humidity value without rounding
    sendEvent(name: 'temperature', value: TempResult) // Send temperature event
    sendEvent(name: 'humidity', value: HumiResult) // Send humidity event
    log.info "Temperature Event Sent: $TempResult, Humidity Event Sent: $HumiResult" // Log events
} else {
    log.error "Invalid data structure received from sensor: ${decodedBody}" // Log error for invalid data
}

def ping() {
    logDebug 'ping' // Log ping event
    poll() // Trigger poll
}

def initialize() {
    log.info 'initialize' // Log initialization
    if (txtEnable) { log.info 'initialize' } // Log if text logging is enabled
    refresh() // Refresh state
}

def logsOff() {
    log.warn 'Debug logging auto disabled...' // Log auto-disable of debug logging
    device.updateSetting('debugOutput', [value: 'false', type: 'bool']) // Update setting
}

def autorefresh() {
    if (debugOutput) { log.info "Getting last refresh date in ${locale} format." } // Log refresh date format
    def dateFormat = (locale == 'UK') ? 'd/MM/YYYY' : 'MM/d/YYYY' // Set date format based on locale
    state.LastRefresh = new Date().format("${dateFormat} \n HH:mm:ss", location.timeZone) // Update last refresh
    sendEvent(name: 'LastRefresh', value: state.LastRefresh, descriptionText: 'Last refresh performed') // Send event
    log.info "Executing 'auto refresh'" // Log auto refresh execution
    refresh() // Refresh state
}

def refresh() {
    logDebug 'Refresh - Getting Status' // Log refresh initiation
    def myPath = '/' + deviceSerial + '&Stats/json' // Set request path
    log.debug "Requesting data from path: $myPath" // Log request path

    def currentPort = port ?: '80' // Use specified port or default to 80
    sendHubCommand(new hubitat.device.HubAction(
        method: 'GET', // Set method to GET
        path: myPath, // Set request path
        headers: [
            HOST: getSensorAddress(currentPort), // Get sensor address with current port
            'Content-Type': 'application/x-www-form-urlencoded' // Set content type
        ]
    ))

    state.LastRefresh = new Date().format('MM/d/YYYY \n HH:mm:ss', location.timeZone) // Update last refresh timestamp
    log.debug "Last refresh timestamp updated to: ${state.LastRefresh}" // Log timestamp
}

private logDebug(msg) {
    if (settings?.debugOutput || settings?.debugOutput == null) { // Check if debug is enabled
        log.debug "$msg" // Log debug message
    }
}

def poll() {
    autorefresh() // Trigger auto refresh
}

private getSensorAddress(port) {
    if (ip && ip instanceof String) { // Check if IP is valid
        def iphex = ip.tokenize('.').collect { String.format('%02x', it.toInteger()) }
            .join().toUpperCase() // Convert IP to hex
        def porthex = String.format('%04x', port.toInteger()) // Convert port to hex
        def sensorAddress = iphex + ':' + porthex // Combine IP and port hex
        if (txtEnable) {
            log.info "Using IP $ip, PORT $port and HEX ADDRESS $sensorAddress for device: ${device.id}" // Log address used
        }
        return sensorAddress // Return sensor address
    } else {
        log.error 'Invalid IP address' // Log error if IP is invalid
        return null // Return null if invalid
    }
}

private dbCleanUp() {
    unschedule() // Unschedule all scheduled jobs
}
