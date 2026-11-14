package app.orcaandroid.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Which sentence a failed print / send gets: "did not answer" only when nothing answered at the printer's address. */
class PrinterFailureTest {
    @Test
    fun nothingAnsweringIsUnreachable() {
        assertTrue(PrinterFailure(java.net.ConnectException("failed to connect to /192.0.2.7 (port 990): isConnected failed: EHOSTUNREACH (No route to host)")).unreachable)
        assertTrue(PrinterFailure(java.net.SocketTimeoutException("Read timed out")).unreachable)
        assertTrue(PrinterFailure(java.net.UnknownHostException("printer.local")).unreachable)
        assertTrue(PrinterFailure(IOException("The printer did not answer on 192.0.2.7 (MQTT :8883)")).unreachable)
        // The cloud fallback's message starts with the LAN failure.
        assertTrue(PrinterFailure(IOException("The printer did not answer on 192.0.2.7 (MQTT :8883)\nSign in to your Bambu account to reach the printer through the cloud.")).unreachable)
        // A wrapped cause counts too.
        assertTrue(PrinterFailure(IOException("upload failed", java.net.NoRouteToHostException("No route to host"))).unreachable)
    }

    @Test
    fun aRefusalOrAMissingSettingKeepsItsOwnSentence() {
        for (text in listOf("Login refused – check the access code", "Enter the printer's serial number", "Enter the printer's IP address and access code",
            "Import your slicer credentials first (missing: slicer_cert.pem)", "This printer type does not support uploads from the app",
            "The printer refused \"project_file\": busy")) {
            assertFalse(text, PrinterFailure(IOException(text)).unreachable)
            assertFalse(text, hasRawNetworkText(text))
        }
    }

    @Test
    fun rawTextGoesUnderDetails() {
        for (text in listOf("Sending to the printer failed (obn -3)", "Connection to the printer failed (obn -1)\nmqtt connect: tls setup",
            "No printer API at http://192.0.2.7 (got a web page instead of data)", "FTP: 550 Requested action not taken", "HTTP 401",
            "failed to connect to /192.0.2.7 (port 990)", "connect failed: ECONNREFUSED (Connection refused)", "host:8883 closed")) {
            assertTrue(text, hasRawNetworkText(text))
        }
        // Not unreachable, but raw: the plain "Sending to the printer failed." sentence with the text under Details.
        assertFalse(PrinterFailure(IOException("Sending to the printer failed (obn -3)")).unreachable)
        assertFalse(PrinterFailure(IOException("HTTP 401")).unreachable)
    }
}
