import groovy.json.*

/*
 * pp-Code H&T Sensor
 * Version: 1.0.0
 * Attribution: Initial base code from https://buy.watchman.online/HubitatDriverWatchman.pdf
 * Author: kb9gxk (Jeff Parrish)
 */

metadata {
    definition(
        name: 'pp-Code H&T Sensor',
        namespace: 'ppcode',
        author: 'kb9gxk (Jeff Parrish)',
        importUrl: 'https://raw.githubusercontent.com/kb9gxk/hubitat/refs/heads/main/hubitat-ppcode.groovy'
    ) {
        capability 'TemperatureMeasurement' // Capability for temperature measurement
        capability 'RelativeHumidityMeasurement' // Capability for humidity measurement
        capability 'Sensor' // General sensor capability
        capability 'Refresh' // Capability for refreshing data
        capability 'Polling' // Capability for polling data
    }
    preferences {
        // Input for sensor IP address
        input('ip', 'string', title: 'IP', description: 'Sensor IP Address', defaultValue: '',
            required: false, displayDuringSetup: true)
        // Input for sensor TCP port
        input('port', 'string', title: 'PORT', description: 'Sensor TCP Port', defaultValue: '80',
            required: false, displayDuringSetup: true)
        // Input for device serial number
        input('deviceSerial', 'string', title: 'Serial', description: 'Device Serial Number',
            defaultValue: '', required: false, displayDuringSetup: true)
        // Input for temperature format selection
        input 'tempFormat', 'enum', title: 'Temperature Format', required: false, defaultValue: false,
            options: [F: 'Fahrenheit', C: 'Celsius']
        // Input for auto-refresh interval
        input 'refreshEvery', 'enum', title: 'Enable auto refresh every XX Minutes', required: false,
            defaultValue: false, options: [5: '5 minutes', 10: '10 minutes', 15: '15 minutes', 30: '30 minutes']
        // Input for date format selection
        input 'locale', 'enum', title: 'Choose refresh date format', required: true, defaultValue: true,
            options: [US: 'US MM/DD/YYYY', UK: 'UK DD/MM/YYYY']
        // Input for enabling debug logging
        input name: 'debugOutput', type: 'bool', title: 'Enable debug logging?', defaultValue: true
        // Input for enabling description text logging
        input name: 'txtEnable', type: 'bool', title: 'Enable descriptionText logging', defaultValue: true
        // Input for rounding option
        input 'rounding', 'bool', title: 'Round Temperature and Humidity?', defaultValue: false // New input
    }
}

def installed() {
    log.debug 'Installed' // Log installation
    refresh() // Call refresh to get initial data
}

def updated() {
    log.debug 'Updated' // Log update
    log.info 'Preferences updated...' // Log preference update
    log.warn "Debug logging is: ${debugOutput == true}" // Log debug state
    unschedule() // Unschedule any existing jobs
    // Schedule auto-refresh if selected
    if (refreshEvery) {
        runEvery${refreshEvery}Minutes(autorefresh) // Schedule based on user input
        log.info "Refresh set for every ${refreshEvery} Minutes" // Log refresh schedule
    } else {
        runEvery30Minutes(autorefresh) // Default refresh schedule
        log.info 'Refresh set for every 30 Minutes' // Log default refresh schedule
    }
    if (debugOutput) { runIn(1800, logsOff) } // Disable debug logging after 30 minutes
    state.LastRefresh = new Date().format('YYYY/MM/dd \n HH:mm:ss', location.timeZone) // Set timestamp
    log.debug "Last refresh timestamp set to: ${state.LastRefresh}" // Log last refresh timestamp
    refresh() // Refresh data after update
}

def parse(description) {
    logDebug "Parsing result: $description" // Log parsing info
    def msg = parseLanMessage(description) // Parse LAN message

    log.debug "Headers: ${msg.headers}" // Log message headers
    log.debug "Status: ${msg.status}" // Log message status

    def body = msg.body // Get message body
    log.debug "Raw Body: $body" // Log raw body

    def decodedBody = body // Prepare to decode body
    def data
    try {
        // Attempt to parse JSON data
        data = new groovy.json.JsonSlurper().parseText(decodedBody)
        log.debug "Parsed Data: $data" // Log parsed data
    } catch (Exception e) {
        log.error "Failed to parse JSON: ${e.message} for body: ${decodedBody}" // Log error
        return // Exit on error
    }

    // Check if temperature and humidity data exist
    if (data?.Stats?.Temp && data?.Stats?.Humi) {
        String myTemp = data.Stats.Temp.replaceAll('[F]', '') // Remove 'F'
        String myHumi = data.Stats.Humi.replaceAll('[%]', '') // Remove '%'

        myTemptrimmed = myTemp.substring(0, myTemp.indexOf('.')) // Trim temperature string
        myHumitrimmed = myHumi.substring(0, myHumi.indexOf('.')) // Trim humidity string

        log.debug "Trimmed Temperature: $myTemptrimmed, Trimmed Humidity: $myHumitrimmed" // Log trimmed values

        int myTempint = Integer.parseInt(myTemptrimmed) // Parse trimmed temperature to integer
        String TempResult
        if (tempFormat == 'C') { // Check temperature format
            TempResult = ((myTempint - 32) * 5 / 9) // Convert to Celsius without rounding
            if (rounding) { TempResult = TempResult.round() } // Round if selected
            TempResult += 'C' // Append unit
            log.debug "Converted Temperature to Celsius: $TempResult" // Log conversion
        } else {
            TempResult = myTempint // Keep temperature in Fahrenheit
            if (rounding) { TempResult = TempResult.round() } // Round if selected
            TempResult += 'F' // Append unit
            log.debug "Temperature remains in Fahrenheit: $TempResult" // Log Fahrenheit
        }

        String HumiResult = myHumitrimmed // Use humidity value without rounding
        if (rounding) { HumiResult = HumiResult.round() } // Round if selected
        HumiResult += ' %' // Append unit
        sendEvent(name: 'temperature', value: TempResult) // Send temperature event
        sendEvent(name: 'humidity', value: HumiResult) // Send humidity event
        log.info "Temperature Event Sent: $TempResult, Humidity Event Sent: $HumiResult" // Log events
    } else {
        log.error "Invalid data structure received from sensor: ${decodedBody}" // Log error for invalid data
    }
}

def ping() {
    logDebug 'ping' // Log ping
    poll() // Call poll function
}

def initialize() {
    log.info 'initialize' // Log initialization
    if (txtEnable) { log.info 'initialize' } // Log if text logging is enabled
    refresh() // Call refresh on initialization
}

def logsOff() {
    log.warn 'Debug logging auto disabled...' // Log auto-disable of debug logging
    device.updateSetting('debugOutput', [value: 'false', type: 'bool']) // Disable debug logging
}

def autorefresh() {
    if (debugOutput) { log.info "Getting last refresh date in ${locale} format." } // Log refresh date format
    def dateFormat = (locale == 'UK') ? 'd/MM/YYYY' : 'MM/d/YYYY' // Determine date format
    state.LastRefresh = new Date().format("${dateFormat} \n HH:mm:ss", location.timeZone) // Set last refresh timestamp
    sendEvent(name: 'LastRefresh', value: state.LastRefresh, descriptionText: 'Last refresh performed') // Send event
    log.info "Executing 'auto refresh'" // Log auto refresh execution
    refresh() // Call refresh
}

def refresh() {
    logDebug 'Refresh - Getting Status' // Log refresh status
    def myPath = '/' + deviceSerial + '&Stats/json' // Construct request path
    log.debug "Requesting data from path: $myPath" // Log request path

    def currentPort = port ?: '80' // Use specified port or default to 80
    sendHubCommand(new hubitat.device.HubAction(
        method: 'GET', // HTTP method
        path: myPath, // Request path
        headers: [
            HOST: getSensorAddress(currentPort), // Sensor address
            'Content-Type': 'application/x-www-form-urlencoded' // Content type
        ]
    ))

    state.LastRefresh = new Date().format('MM/d/YYYY \n HH:mm:ss', location.timeZone) // Update last refresh timestamp
    log.debug "Last refresh timestamp updated to: ${state.LastRefresh}" // Log updated timestamp
}

private logDebug(msg) {
    // Log debug messages if enabled
    if (settings?.debugOutput || settings?.debugOutput == null) {
        log.debug "$msg" // Log the message
    }
}

def poll() {
    autorefresh() // Call auto refresh
}

private getSensorAddress(port) {
    // Validate and construct sensor address
    if (ip && ip instanceof String) {
        def iphex = ip.tokenize('.').collect { String.format('%02x', it.toInteger()) }
            .join().toUpperCase() // Convert IP to hex
        def porthex = String.format('%04x', port.toInteger()) // Convert port to hex
        def sensorAddress = iphex + ':' + porthex // Combine IP and port
        if (txtEnable) {
            log.info "Using IP $ip, PORT $port and HEX ADDRESS $sensorAddress for device: ${device.id}" // Log address
        }
        return sensorAddress // Return the sensor address
    } else {
        log.error 'Invalid IP address' // Log error for invalid IP
        return null // Return null for invalid address
    }
}

private dbCleanUp() {
    unschedule() // Unschedule any jobs
}
