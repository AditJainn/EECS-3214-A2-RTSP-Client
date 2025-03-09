/*
 * Author: Jonatan Schroeder
 * Updated: October 2022
 *
 * This code may not be used without written consent of the authors.
 */

package ca.yorku.rtsp.client.net;

import ca.yorku.rtsp.client.exception.RTSPException;
import ca.yorku.rtsp.client.model.Frame;
import ca.yorku.rtsp.client.model.Session;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * This class represents a connection with an RTSP server.
 */
public class RTSPConnection {

    private static final int BUFFER_LENGTH = 0x10000;
    private final Session session;
    private Socket connectionSocket;
    private PrintWriter cOut;
    private BufferedReader cIn;
    private RTSPResponse response = null;
    private DatagramSocket videoSocket = null;
    RTPReceivingThread videdoThread = new RTPReceivingThread();

    // private PrintWriter vOut;
    // private BufferedReader vIn;

    // TODO Add additional fields, if necessary

    /**
     * Establishes a new connection with an RTSP server. No message is
     * sent at this point, and no stream is set up.
     *
     * @param session The Session object to be used for connectivity with the UI.
     * @param server  The hostname or IP address of the server.
     * @param port    The TCP port number where the server is listening to.
     * @throws RTSPException If the connection couldn't be accepted,
     *                       such as if the host name or port number
     *                       are invalid or there is no connectivity.
     */
    public RTSPConnection(Session session, String server, int port) throws RTSPException {
        System.out.printf("session : %s \n Server : %s \n Port: %d \n ", session, server, port);

        try {
            connectionSocket = new Socket(server, port);
            cIn = new BufferedReader(new InputStreamReader(connectionSocket.getInputStream()));
            cOut = new PrintWriter(connectionSocket.getOutputStream(), true);
        } catch (IOException e) {
            throw new RTSPException("Could not connect" + e.getMessage());
        }
        this.session = session;
        // session.get

        // TODO
    }

    /**
     * Sets up a new video stream with the server. This method is
     * responsible for sending the SETUP request, receiving the
     * response and retrieving the session identification to be used
     * in future messages. It is also responsible for establishing an
     * RTP datagram socket to be used for data transmission by the
     * server. The datagram socket should be created with a random
     * available UDP port number, and the port number used in that
     * connection has to be sent to the RTSP server for setup. This
     * datagram socket should also be defined to timeout after 2
     * seconds if no packet is received.
     *
     * @param videoName The name of the video to be setup.
     * @throws RTSPException If there was an error sending or
     *                       receiving the RTSP data, or if the RTP
     *                       socket could not be created, or if the
     *                       server did not return a successful
     *                       response.
     */

    // TO DO make port random

    public synchronized void setup(String videoName) throws RTSPException {
        int randomPort = (int) (Math.random() * (6000)) + 1024;
        while (videoSocket == null) {
            try {
                videoSocket = new DatagramSocket(randomPort);
                videoSocket.setSoTimeout(2000);
                // If the socket is created successfully, it means the port is available
            } catch (Exception e) {
                randomPort = (int) (Math.random() * (6000)) + 1024;
                videoSocket = null;
                // If there is an exception, the port is already in use
            }
        }
        String request = "SETUP movie1.Mjpeg RTSP/1.0\nCSeq: 1\nTransport: RTP/UDP; client_port=" + randomPort + "\r\n";
        System.out.println("SETUP request Sent: \n" + request);

        cOut.println(request);

        // prone to bug here!! just leaving it for now - > response may not always be 3
        // lines
        try {
            response = readRTSPResponse();
            System.out.println("SETUP Response Receieved:\n" + response);
        } catch (Exception e) {
            e.printStackTrace();
            // TODO: handle exception
        }

        // TODO
    }

    /**
     * Starts (or resumes) the playback of a set up stream. This
     * method is responsible for sending the request, receiving the
     * response and, in case of a successful response, starting a
     * separate thread responsible for receiving RTP packets with
     * frames (achieved by calling start() on a new object of type
     * RTPReceivingThread).
     *
     * @throws RTSPException If there was an error sending or
     *                       receiving the RTSP data, or if the server
     *                       did not return a successful response.
     */
    public synchronized void play() throws RTSPException {
        // videoSocket // is our socket .
        String request = "PLAY movie1.Mjpeg RTSP/1.0\nCSeq: 2\n" + response.getResponseMessage() + "\n";

        System.out.println("Play Request Sent: \n" + request);
        cOut.println(request);

        try {
            System.out.println("PLAY Response Receieved:\n");
            System.out.println("1." + cIn.readLine()); // I think is
            System.out.println("2." + cIn.readLine());
            System.out.println("3." + cIn.readLine());
            System.out.println("4." + cIn.readLine());

        } catch (Exception e) {
            e.printStackTrace();
            // TODO: handle exception
        }
        System.out.println("Thread started: " + Thread.currentThread().getName());
        System.out.println("Active thread count: " + Thread.activeCount());
        videdoThread.start();
        // System.out.println("Active thread count: " + Thread.activeCount());
        // Thread.getAllStackTraces().keySet().forEach(t ->
        // System.out.println("Thread: " + t.getName() + " State: " + t.getState()));
        // System.out.println(videdoThread.isAlive());
        // TODO

    }

    private class RTPReceivingThread extends Thread {

        /**
         * Continuously receives RTP packets until the thread is
         * cancelled or until an RTP packet is received with a
         * zero-length payload. Each packet received from the datagram
         * socket is assumed to be no larger than BUFFER_LENGTH
         * bytes. This data is then parsed into a Frame object (using
         * the parseRTPPacket() method) and the method
         * session.processReceivedFrame() is called with the resulting
         * packet. The receiving process should be configured to
         * timeout if no RTP packet is received after two seconds. If
         * a frame with zero-length payload is received, indicating
         * the end of the stream, the method session.videoEnded() is
         * called, and the thread is terminated.
         */
        @Override
        public void run() {
            System.out.println("Thread started: " + Thread.currentThread().getName());
            Frame f = null;
            byte[] buffer = new byte[BUFFER_LENGTH];
            DatagramPacket packet = new DatagramPacket(buffer, BUFFER_LENGTH);
            try {
                videoSocket.receive(packet);
                f = parseRTPPacket(packet);
                while (f.getPayloadLength() != 0) {
                    videoSocket.receive(packet);
                    f = parseRTPPacket(packet);
                    session.processReceivedFrame(f);
                }
            } catch (Exception e) {
                e.printStackTrace();

                // TODO: handle exception
            }
            // TODO
        }

    }

    /**
     * Pauses the playback of a set up stream. This method is
     * responsible for sending the request, receiving the response
     * and, in case of a successful response, stopping the thread
     * responsible for receiving RTP packets with frames.
     *
     * @throws RTSPException If there was an error sending or
     *                       receiving the RTSP data, or if the server
     *                       did not return a successful response.
     */
    public synchronized void pause() throws RTSPException {

        // TODO
    }

    /**
     * Terminates a set up stream. This method is responsible for
     * sending the request, receiving the response and, in case of a
     * successful response, closing the RTP socket. This method does
     * not close the RTSP connection, and a further SETUP in the same
     * connection should be accepted. Also, this method can be called
     * both for a paused and for a playing stream, so the thread
     * responsible for receiving RTP packets will also be cancelled,
     * if active.
     *
     * @throws RTSPException If there was an error sending or
     *                       receiving the RTSP data, or if the server
     *                       did not return a successful response.
     */
    public synchronized void teardown() throws RTSPException {

        // TODO
    }

    /**
     * Closes the connection with the RTSP server. This method should
     * also close any open resource associated to this connection,
     * such as the RTP connection and thread, if it is still open.
     */
    public synchronized void closeConnection() {

        // TODO
    }

    /**
     * Parses an RTP packet into a Frame object. This method is
     * intended to be a helper method in this class, but it is made
     * public to facilitate testing.
     *
     * @param packet the byte representation of a frame, corresponding to the RTP
     *               packet.
     * @return A Frame object.
     */
    public static Frame parseRTPPacket(DatagramPacket packet) {
        byte[] data = packet.getData();
        int version = (data[0] >> 6) & 0b11; // First 2 bits
        int padding = (data[0] >> 5) & 0b1; // Next bit
        int extension = (data[0] >> 4) & 0b1; // Next bit
        int csrcCount = data[0] & 0b1111; // Last 4 bits
        boolean marker = ((data[1] >> 7) & 0b1) == 1; // First bit of second byte
        byte payloadType = (byte) (data[1] & 0b01111111);
        short sequenceNumber = (short) (((data[2] & 0xFF) << 8) | (data[3] & 0xFF));
        int timestamp = ((data[4] & 0xFF) << 24) | ((data[5] & 0xFF) << 16) | ((data[6] & 0xFF) << 8)
                | ((data[7] & 0xFF));
        long ssrc = ((long) (data[8] & 0xFF) << 24) | ((long) (data[9] & 0xFF) << 16) |
                ((long) (data[10] & 0xFF) << 8) | ((long) (data[11] & 0xFF));

        // Step 4: Extract Payload (Skipping RTP Header: 12 bytes + CSRC count * 4
        // bytes)
        int headerSize = 12 + (csrcCount * 4);
        byte[] payload = new byte[packet.getLength() - headerSize];
        System.arraycopy(data, headerSize, payload, 0, payload.length);

        // // Step 5: Print RTP Header Info
        // System.out.println("Received RTP Packet:");
        // // System.out.println("Version: " + version);
        // // System.out.println("Padding: " + padding);
        // // System.out.println("Extension: " + extension);
        // // System.out.println("CSRC Count: " + csrcCount);
        // System.out.println("Marker: " + marker);
        // System.out.println("Payload Type: " + payloadType);
        // System.out.println("Sequence Number: " + sequenceNumber);
        // System.out.println("Timestamp: " + timestamp);
        // // System.out.println("SSRC: " + ssrc);
        // System.out.println("Payload Size: " + payload.length);
        // System.out.println("--------------------------------");
        Frame f = new Frame(payloadType, marker, sequenceNumber, timestamp, payload);

        // TODO
        return f;
    }

    /**
     * Reads and parses an RTSP response from the socket's input. This
     * method is intended to be a helper method in this class, but it
     * is made public to facilitate testing.
     *
     * @return An RTSPResponse object if the response was read
     *         completely, or null if the end of the stream was reached.
     * @throws IOException   In case of an I/O error, such as loss of connectivity.
     * @throws RTSPException If the response doesn't match the expected format.
     */

    // We need to deal with the issue of maybe not getting 3 lines
    public RTSPResponse readRTSPResponse() throws IOException, RTSPException {
        // TODO

        try {
            System.out.println("Reading in RTSP response");
            String version = cIn.readLine();
            String responseCode = cIn.readLine().split(": ")[1];
            String message = cIn.readLine();
            return new RTSPResponse(version, Integer.parseInt(responseCode), message);
        } catch (IOException e) {
            e.printStackTrace();
        }

        return null; // Replace with a proper RTSPResponse
    }

}
