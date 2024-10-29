import groovy.json.*

/*
 * pp-Code H&T Sensor
 * Version: 1.0.1  // Updated version
 * Attribution: Initial base code from https://buy.watchman.online/HubitatDriverWatchman.pdf
 * Additional code assistance from ChatGPT, OpenAI
 * Author: kb9gxk (Jeff Parrish)
 * 
 * Changes (Version 1.0.1):
 * - Moved the rounding option input to be after the serial number in preferences.
 * - Updated rounding logic to round to whole numbers only if the rounding option is enabled.
 * - Retained original float values (including decimals) when rounding is disabled.
 */

metadata {
    definition(
        name: 'pp-Code H&T Sensor',
        namespace: 'ppcode',
        author: 'kb9gxk (Jeff Parrish)',
        importUrl: 'https://raw.githubusercontent.com/kb9gxk/hubitat/refs/heads/main/hubitat-ppcode.groovy'
    ) {
        capability 'TemperatureMeasurement'
        capability 'RelativeHumidityMeasurement'
        capability 'Sensor'
        capability 'Refresh'
        capability 'Polling'
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
        // Input for rounding option, defaulting to false
        input 'rounding', 'bool', title: 'Round Temperature and Humidity?', defaultValue: false
        // Input for temperature format selection (Fahrenheit or Celsius)
        input 'tempFormat', 'enum', title: 'Temperature Format', required: false, defaultValue: 'F',
            options: [F: 'Fahrenheit', C: 'Celsius']
        // Input for auto-refresh interval
        input 'refreshEvery', 'enum', title: 'Enable auto refresh every XX Minutes', required: false,
            defaultValue: '30', options: [5: '5 minutes', 10: '10 minutes', 15: '15 minutes', 30: '30 minutes']
        // Input for date format selection
        input 'locale', 'enum', title: 'Choose refresh date format', required: true, defaultValue: 'US',
            options: [US: 'US MM/DD/YYYY', UK: 'UK DD/MM/YYYY']
        // Input for enabling debug logging
        input name: 'debugOutput', type: 'bool', title: 'Enable debug logging?', defaultValue: true
        // Input for enabling description text logging
        input name: 'txtEnable', type: 'bool', title: 'Enable descriptionText logging', defaultValue: true
    }
}

def installed() {
    log.debug 'Installed' // Log installation event
    refresh() // Call refresh to get initial data from the sensor
}

def updated() {
    log.debug 'Updated' // Log update event
    log.info 'Preferences updated...' // Log preference update
    log.warn "Debug logging is: ${debugOutput == true}" // Log debug state
    unschedule() // Unschedule any existing jobs

    // Schedule auto-refresh if selected
    if (refreshEvery) {
        runEveryXMinutes(refreshEvery.toInteger(), autorefresh) // Schedule based on user preference
        log.info "Refresh set for every ${refreshEvery} Minutes" // Log refresh schedule
    } else {
        runEveryXMinutes(30, autorefresh) // Default to every 30 minutes
        log.info 'Refresh set for every 30 Minutes' // Log default schedule
    }

    if (debugOutput) { runIn(1800, logsOff) } // Disable debug logging after 30 minutes
    state.LastRefresh = new Date().format('YYYY/MM/dd \n HH:mm:ss', location.timeZone) // Last refresh timestamp
    log.debug "Last refresh timestamp set to: ${state.LastRefresh}" // Log last refresh timestamp
    refresh() // Refresh data after update
}

def parse(description) {
    logDebug "Parsing result: $description" // Log the description being parsed
    def msg = parseLanMessage(description) // Parse LAN message

    log.debug "Headers: ${msg.headers}" // Log message headers
    log.debug "Status: ${msg.status}" // Log message status

    def body = msg.body // Extract message body
    log.debug "Raw Body: $body" // Log raw body content

    def decodedBody = body // Prepare for decoding
    def data
    try {
        // Attempt to parse JSON data
        data = new groovy.json.JsonSlurper().parseText(decodedBody)
        log.debug "Parsed Data: $data" // Log parsed data
    } catch (Exception e) {
        log.error "Failed to parse JSON: ${e.message} for body: ${decodedBody}" // Log error on failure
        return // Exit if parsing fails
    }

    if (data?.Stats?.Temp && data?.Stats?.Humi) {
        // Extract temperature and humidity as strings
        String myTemp = data.Stats.Temp.replaceAll('[F]', '') // Remove 'F'
        String myHumi = data.Stats.Humi.replaceAll('[%]', '') // Remove '%'

        // Parse as floats to retain decimal values
        float myTempFloat = Float.parseFloat(myTemp)
        float myHumiFloat = Float.parseFloat(myHumi)

        log.debug "Raw Temperature: $myTempFloat, Raw Humidity: $myHumiFloat" // Log raw values

        String TempResult
        if (tempFormat == 'C') { // Check temperature format
            float tempInCelsius = (myTempFloat - 32) * 5 / 9 // Convert to Celsius
            log.debug "Rounding setting is: ${rounding}" // Log rounding setting
            if (rounding) {
                log.debug "Rounding is enabled. Original Temp: $tempInCelsius" // Log before rounding
                tempInCelsius = Math.round(tempInCelsius) // Round to nearest whole number
                TempResult = "${tempInCelsius.toInteger()}C" // Format with unit, convert to integer
            } else {
                TempResult = "${tempInCelsius}C" // Keep original float value with decimals
            }
            log.debug "Converted Temperature to Celsius: $TempResult" // Log conversion
        } else {
            if (rounding) {
                log.debug "Rounding is enabled. Original Temp: $myTempFloat" // Log before rounding
                TempResult = "${Math.round(myTempFloat)}F" // Round to nearest whole number
            } else {
                TempResult = "${myTempFloat}F" // Keep original float value with decimals
            }
            log.debug "Temperature remains in Fahrenheit: $TempResult" // Log Fahrenheit
        }

        String HumiResult
        log.debug "Rounding setting is: ${rounding}" // Log rounding setting
        if (rounding) {
            log.debug "Rounding is enabled. Original Humidity: $myHumiFloat" // Log before rounding
            myHumiFloat = Math.round(myHumiFloat) // Round to nearest whole number
            HumiResult = "${myHumiFloat.toInteger()} %" // Append unit, convert to integer
        } else {
            HumiResult = "${myHumiFloat} %" // Keep original float value with decimals
        }

        // Send events for temperature and humidity
        sendEvent(name: 'temperature', value: TempResult) // Send temperature event
        sendEvent(name: 'humidity', value: HumiResult) // Send humidity event
        log.info "Temperature Event Sent: $TempResult, Humidity Event Sent: $HumiResult" // Log events
    } else {
        log.error "Invalid data structure received from sensor: ${decodedBody}" // Log error for invalid data
    }
}

def ping() {
    logDebug 'ping' // Log ping action
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
    state.LastRefresh = new Date().format("${dateFormat} \n HH:mm:ss", location.timeZone) // Update timestamp
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
        method: 'GET', // HTTP method for the request
        path: myPath, // Request path
        headers: [
            HOST: getSensorAddress(currentPort), // Sensor address
            'Content-Type': 'application/x-www-form-urlencoded' // Content type
        ]
    ))

    // Update last refresh timestamp
    state.LastRefresh = new Date().format('MM/d/YYYY \n HH:mm:ss', location.timeZone) 
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
