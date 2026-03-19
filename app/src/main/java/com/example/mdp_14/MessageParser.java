package com.example.mdp_14;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Parses incoming JSON messages from the robot and delegates to appropriate handlers
 */
public class MessageParser {
    private static final String TAG = "MessageParser";

    private MessageCallback callback;

    public interface MessageCallback {
        void onStatusUpdate(String status);
        void onImageRecognition(String imageId, int obstacleId);
        void onRobotLocationUpdate(int x, int y, int direction);
    }

    public MessageParser(MessageCallback callback) {
        this.callback = callback;
    }

    /**
     * Parse incoming message and delegate to appropriate handler
     */
    public void parseMessage(String message) {
        try {
            // Check if message contains JSON markers
            if (message.contains("status")) {
                parseStatusMessage(message);
            } else if (message.contains("image-rec")) {
                parseImageRecognitionMessage(message);
            } else if (message.contains("location")) {
                parseLocationMessage(message);
            } else {
                Log.d(TAG, "Unknown message type: " + message);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing message: " + message, e);
        }
    }

    /**
     * Parse STATUS message
     * Format: {"cat": "status", "value": <status>}
     */
    private void parseStatusMessage(String message) {
        try {
            JSONObject json = new JSONObject(message);
            String status = json.getString("value");

            if (callback != null) {
                callback.onStatusUpdate(status);
            }
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse status message: " + message, e);
        }
    }

    /**
     * Parse IMAGE-REC message
     * Format: {"cat": "image-rec", "value": {"image_id": <id>, "obstacle_id": <id>}}
     */
    private void parseImageRecognitionMessage(String message) {
        try {
            JSONObject json = new JSONObject(message);

            // Verify category
            if (!"image-rec".equals(json.getString("cat"))) {
                Log.w(TAG, "Unexpected message category: " + json.getString("cat"));
                return;
            }

            JSONObject value = json.getJSONObject("value");
            String imageId = value.getString("image_id");
            int obstacleId = value.getInt("obstacle_id");

            if (callback != null) {
                callback.onImageRecognition(imageId, obstacleId);
            }
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse image-rec message: " + message, e);
        }
    }

    /**
     * Parse LOCATION message
     * Format: {"cat": "location", "value": {"x": <x>, "y": <y>, "d": <direction>}}
     */
    private void parseLocationMessage(String message) {
        try {
            JSONObject json = new JSONObject(message);

            // Verify category
            if (!"location".equals(json.getString("cat"))) {
                Log.w(TAG, "Unexpected message category: " + json.getString("cat"));
                return;
            }

            JSONObject value = json.getJSONObject("value");
            int x = value.getInt("x");
            int y = value.getInt("y");
            int direction = value.getInt("d");

            if (callback != null) {
                callback.onRobotLocationUpdate(x, y, direction);
            }
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse location message: " + message, e);
        }
    }
}