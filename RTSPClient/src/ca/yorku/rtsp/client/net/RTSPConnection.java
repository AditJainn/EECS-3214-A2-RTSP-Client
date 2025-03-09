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

/**
 * This class represents a connection with an RTSP server.
 */
public class RTSPConnection {

    // =========================
    // RTSP (Control) - TCP Socket Fields
    // =========================
    // To avoid confusion between RTSP and RTP, use "control" and "data" for variable names
    private final Socket controlSocket;
    private final Session session;
    private final PrintWriter controlWriter;
    private final BufferedReader controlReader;
    private RTSPResponse response = null;
    private int cSeq = 1;
    private boolean paused;
    private boolean videoSetup;
    private boolean movieCompleted;


    // =========================
    // RTP (Data) - UDP Socket Fields
    // =========================
    private DatagramSocket videoSocket = null;
    private static final int BUFFER_LENGTH = 0x10000;
    RTPReceivingThread videoThread = new RTPReceivingThread();

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
        try {
            controlSocket = new Socket(server, port);
            controlReader = new BufferedReader(new InputStreamReader(controlSocket.getInputStream()));
            controlWriter = new PrintWriter(controlSocket.getOutputStream(), true);
        } catch (IOException e) {
            throw new RTSPException("RTSP connection failed: " + e.getMessage());
        }
        this.session = session;
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
    public synchronized void setup(String videoName) throws RTSPException {
        
        if (videoSocket != null ){ // already exsists
            teardown();
        }
        try {
            videoSocket = new DatagramSocket();
            videoSocket.setSoTimeout(2000);
            controlWriter.println(String.format("SETUP %s RTSP/1.0\nCSeq: %d\nTransport: RTP/UDP; client_port=%d\r\n",
                    videoName, cSeq++, videoSocket.getLocalPort()));
        } catch (IOException e) {
            throw new RTSPException("RTP connection failed: " + e.getMessage());
        }

        try {
            response = readRTSPResponse();
            validateResponse();
        } catch (Exception e) {
            throw new RTSPException("Unable to receive response from server: " + e.getMessage());
        }
    }

    /**
     * Starts (or resumes) the playback of a setup stream. This
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
        // if 
        if (videoSocket == null ){ // deals with non-setup movie play requests.
            System.out.println("NO MOVIE HAS BEEN SET UP");
            return;
        }
        if (videoThread != null & videoThread.isMovieCompleted() == true) {
            movieCompleted = true;
            System.out.println("Nothing in buffer");
            return;
        }
        paused = false;
        String request = generateRequest("PLAY");
        controlWriter.println(request);

        try {
            response = readRTSPResponse();
            validateResponse();
        } catch (IOException e) {
            throw new RTSPException(e);
        }

        if (videoThread == null || !videoThread.isAlive()) {
            movieCompleted = false;
            System.out.println("Thread started: " + Thread.currentThread().getName());
            System.out.println("Active thread count: " + Thread.activeCount());
            videoThread = new RTPReceivingThread();
            videoThread.start();
        }
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
        boolean movieCompleted = false; 
        public boolean isMovieCompleted() {
            return movieCompleted;
        }
        
        @Override
        public void run() {
            System.out.println("Thread started: " + Thread.currentThread().getName());
            Frame f = null;
            byte[] buffer = new byte[BUFFER_LENGTH];
            DatagramPacket packet = new DatagramPacket(buffer, BUFFER_LENGTH);

            while (!Thread.interrupted()) {
                try {
                    videoSocket.receive(packet);
                    f = parseRTPPacket(packet);
                    if (f.getPayloadLength() == 0) {
                        movieCompleted = true;
                        session.videoEnded(cSeq);
                        break;
                    }

                    session.processReceivedFrame(f);
                } catch (SocketTimeoutException e) {
                    if (paused) System.out.println("Timeout expected.");
                    else throw new RuntimeException(e);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            
        }
    }

    /**
     * Pauses the playback of a setup stream. This method is
     * responsible for sending the request, receiving the response
     * and, in case of a successful response, stopping the thread
     * responsible for receiving RTP packets with frames.
     *
     * @throws RTSPException If there was an error sending or
     *                       receiving the RTSP data, or if the server
     *                       did not return a successful response.
     */
    public synchronized void pause() throws RTSPException {
        if (videoSocket == null){
            System.out.println("NO MOVIE HAS BEEN SET UP");
            return;
        }
        if (videoThread != null & videoThread.isMovieCompleted() == true) {
            movieCompleted = true;
            System.out.println("Nothing to pause");
            return;
        }
        else if( paused ){
            System.out.println("Movie has alraedy been paused");
            return;
        }

        paused = true;
        controlWriter.println(generateRequest("PAUSE"));

        try {
            response = readRTSPResponse();
            validateResponse();
        } catch (IOException e) {
            throw new RTSPException(e);
        }

        if (videoThread != null && videoThread.isAlive()) {
            videoThread.interrupt();
        }
    }

    /**
     * Terminates a setup stream. This method is responsible for
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
        if (response == null || videoSocket == null) return;

        controlWriter.println(generateRequest("TEARDOWN"));

        try {
            response = readRTSPResponse();
            validateResponse();
        } catch (IOException e) {
            throw new RTSPException("Failed to teardown: " + e.getMessage());
        }

        if (videoThread != null && videoThread.isAlive()) {
            videoThread.interrupt();
        }

        if (videoSocket != null && !videoSocket.isClosed()) {
            videoSocket.close();
            videoSocket = null;
        }
    }

    /**
     * Closes the connection with the RTSP server. This method should
     * also close any open resource associated to this connection,
     * such as the RTP connection and thread, if it is still open.
     */
    public synchronized void closeConnection() {
        try {
            teardown();
        } catch (RTSPException e) {
            System.err.println("Failed to teardown stream before closing connection - " + e.getMessage());
        }

        try {
            if (controlSocket != null && !controlSocket.isClosed()) {
                controlSocket.close();
                System.out.println("RTSP control socket closed.");
            }
            

        } catch (IOException e) {
            System.err.println("Failed to close RTSP control socket - " + e.getMessage());
        }

        if (videoSocket != null && !videoSocket.isClosed()) {
            videoSocket.close();
            videoSocket = null;
        }

        if (videoThread != null && videoThread.isAlive()) {
            videoThread.interrupt();
        }
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
     * completely, or null if the end of the stream was reached.
     * @throws IOException   In case of an I/O error, such as loss of connectivity.
     * @throws RTSPException If the response doesn't match the expected format.
     */
    public RTSPResponse readRTSPResponse() throws IOException, RTSPException {
        try {
            String line;
            RTSPResponse response = null;

            while ((line = controlReader.readLine()) != null && !line.trim().isEmpty()) {
                String[] data = line.split(": ");
                if (data.length == 1 && line.contains("RTSP")) {
                    String[] metadata = line.split(" ");
                    response = new RTSPResponse(metadata[0], Integer.parseInt(metadata[1]), metadata[2]);
                } else if (data.length == 2 && response != null) {
                    response.addHeaderValue(data[0], data[1]);
                } else {
                    
                    throw new RTSPException("The response does not match the expected format. \n "+line);
                }
            }

            return response;
        } catch (IOException e) {
            throw new RTSPException(e);
        }
    }

    private String generateRequest(String operation) {
        String request = String.format("%s %s RTSP/1.0\nCSeq: %d\nSession: %s\r\n",
                operation, session.getVideoName(), cSeq++, response.getHeaderValue("session"));
        System.out.println(request);
        return request;
    }

    private void validateResponse() throws RTSPException {
        if (response == null || response.getResponseCode() != 200) {
            throw new RTSPException("RTSP request failed: " + response);
        }
    }
}
