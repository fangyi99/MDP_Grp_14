package com.example.mdp_14;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends AppCompatActivity implements ArenaMapView.OnObstacleActionListener {
    private static final String TAG = "MainActivity";
    private static final int REQUEST_BLUETOOTH_PERMISSIONS = 1;

    // Standard SerialPortService ID
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    // UI Elements - Bluetooth
    private TextView statusText;
    private TextView receivedText;
    private EditText messageInput;
    private Button connectButton;
    private ImageButton sendButton;

    // UI Elements - Arena Map
    private ArenaMapView arenaMapView;
    private Button addObstacleButton;
    private Button editObstacleButton;
    private Button deleteObstacleButton;
    private Button clearAllButton;
    private Button spawnRobotButton;
    private Button sendObstaclesButton;
    private ToggleButton lockToggle;

    // Bluetooth
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothService bluetoothService;
    private boolean isConnected = false;

    /*
     * ============================================================
     * BLUETOOTH PROTOCOL FOR OBSTACLES (C.6 & C.7)
     * ============================================================
     *
     * Format for sending all obstacles:
     *   OBSTACLES,<count>
     *   OBS,<id>,<x>,<y>,<width>,<height>,<target_face>
     *   OBS,<id>,<x>,<y>,<width>,<height>,<target_face>
     *   ...
     *
     * Where:
     *   <id>          = Obstacle number (1, 2, 3, ...)
     *   <x>, <y>      = Grid coordinates (0-19)
     *   <width>       = Width in grid units
     *   <height>      = Height in grid units
     *   <target_face> = N, S, E, W (which face has the target image)
     *
     * Example:
     *   OBSTACLES,3
     *   OBS,1,5,10,1,1,N
     *   OBS,2,8,3,2,1,E
     *   OBS,3,15,15,1,2,S
     *
     * ============================================================
     */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize UI elements
        initializeViews();
        setupBluetoothUI();
        setupArenaMapUI();

        // Get Bluetooth adapter
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not available on this device", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // Initialize Bluetooth service
        bluetoothService = new BluetoothService(messageHandler, bluetoothAdapter);

        // Check permissions on startup
        checkPermissions();

        // Auto-start listening for incoming connections
        startListeningOnStartup();
    }

    private void initializeViews() {
        // Bluetooth views
        statusText = findViewById(R.id.statusText);
        receivedText = findViewById(R.id.receivedText);
        messageInput = findViewById(R.id.messageInput);
        connectButton = findViewById(R.id.connectButton);
        sendButton = findViewById(R.id.sendButton);

        // Arena map views
        arenaMapView = findViewById(R.id.arenaMapView);
        addObstacleButton = findViewById(R.id.addObstacleButton);
        editObstacleButton = findViewById(R.id.editObstacleButton);
        deleteObstacleButton = findViewById(R.id.deleteObstacleButton);
        clearAllButton = findViewById(R.id.clearAllButton);
        spawnRobotButton = findViewById(R.id.spawnRobotButton);
        sendObstaclesButton = findViewById(R.id.sendObstaclesButton);
        lockToggle = findViewById(R.id.lockToggle);
    }

    private void setupBluetoothUI() {
        connectButton.setOnClickListener(v -> {
            if (!isConnected) {
                checkPermissionsAndConnect();
            } else {
                disconnect();
            }
        });

        sendButton.setOnClickListener(v -> {
            String message = messageInput.getText().toString();
            if (!message.isEmpty()) {
                sendMessage(message);
                messageInput.setText("");
            }
        });
    }

    private void setupArenaMapUI() {
        arenaMapView.setOnObstacleActionListener(this);

        // Lock toggle - controls whether elements can be dragged
        lockToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            arenaMapView.setDragLocked(isChecked);
            if (isChecked) {
                Toast.makeText(this, "Map locked - dragging disabled", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Map unlocked - dragging enabled", Toast.LENGTH_SHORT).show();
            }
        });

        // Spawn robot button
        spawnRobotButton.setOnClickListener(v -> {
            if (arenaMapView.hasRobot()) {
                new AlertDialog.Builder(this)
                        .setTitle("Robot")
                        .setMessage("Robot already exists. What would you like to do?")
                        .setPositiveButton("Reset Position", (dialog, which) -> {
                            arenaMapView.spawnRobot();
                            Toast.makeText(this, "Robot reset to start position", Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton("Remove", (dialog, which) -> {
                            arenaMapView.removeRobot();
                            Toast.makeText(this, "Robot removed", Toast.LENGTH_SHORT).show();
                        })
                        .setNeutralButton("Cancel", null)
                        .show();
            } else {
                arenaMapView.spawnRobot();
                Toast.makeText(this, "Robot spawned at bottom-left. Drag to position.", Toast.LENGTH_SHORT).show();
            }
        });

        // Send obstacles button (C.6 & C.7)
        sendObstaclesButton.setOnClickListener(v -> sendAllObstaclesToRobot());

        addObstacleButton.setOnClickListener(v -> showAddObstacleDialog());

        editObstacleButton.setOnClickListener(v -> {
            Obstacle selected = arenaMapView.getSelectedObstacle();
            if (selected != null) {
                showEditObstacleDialog(selected);
            } else {
                Toast.makeText(this, "Please select an obstacle first", Toast.LENGTH_SHORT).show();
            }
        });

        deleteObstacleButton.setOnClickListener(v -> {
            Obstacle selected = arenaMapView.getSelectedObstacle();
            if (selected != null) {
                arenaMapView.removeObstacle(selected);
                Toast.makeText(this, "Obstacle deleted", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Please select an obstacle first", Toast.LENGTH_SHORT).show();
            }
        });

        clearAllButton.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("Clear All")
                    .setMessage("Remove all obstacles and robot?")
                    .setPositiveButton("Yes", (dialog, which) -> {
                        arenaMapView.clearObstacles();
                        arenaMapView.removeRobot();
                        Toast.makeText(this, "All cleared", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("No", null)
                    .show();
        });
    }

    // ============================================================
    // C.6 & C.7: OBSTACLE BLUETOOTH TRANSMISSION
    // ============================================================

    /**
     * Send all obstacles to robot via Bluetooth.
     * Format: OBSTACLES,<count> followed by individual OBS lines
     */
    private void sendAllObstaclesToRobot() {
        List<Obstacle> obstacles = arenaMapView.getObstacles();

        if (!isConnected) {
            Toast.makeText(this, "Not connected to robot", Toast.LENGTH_SHORT).show();
            return;
        }

        if (obstacles.isEmpty()) {
            Toast.makeText(this, "No obstacles to send", Toast.LENGTH_SHORT).show();
            return;
        }

        // Send header with obstacle count
        sendMessage("OBSTACLES," + obstacles.size());

        // Send each obstacle
        for (Obstacle obs : obstacles) {
            String obsString = formatObstacleString(obs);
            sendMessage(obsString);
        }

        Toast.makeText(this, "Sent " + obstacles.size() + " obstacle(s) to robot", Toast.LENGTH_SHORT).show();
        Log.d(TAG, "Sent " + obstacles.size() + " obstacles to robot");
    }

    /**
     * Format a single obstacle as a string for Bluetooth transmission.
     * Format: OBS,<id>,<x>,<y>,<width>,<height>,<target_face>
     */
    private String formatObstacleString(Obstacle obs) {
        String targetFace = obs.getTargetFace().name().substring(0, 1); // N, S, E, or W

        return String.format("OBS,%d,%d,%d,%d,%d,%s",
                obs.getId(),
                obs.getGridX(),
                obs.getGridY(),
                obs.getWidth(),
                obs.getHeight(),
                targetFace);
    }

    /**
     * Send a single obstacle update (for real-time updates when obstacle moves/changes)
     */
    private void sendObstacleUpdate(Obstacle obs) {
        if (isConnected) {
            String obsString = formatObstacleString(obs);
            sendMessage("OBS_UPDATE," + obsString.substring(4)); // Remove "OBS," prefix and add "OBS_UPDATE,"
            Log.d(TAG, "Sent obstacle update: " + obsString);
        }
    }

    // ============================================================
    // OBSTACLE DIALOGS
    // ============================================================

    /**
     * Show dialog to add a new obstacle with configurable dimensions
     */
    private void showAddObstacleDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_obstacle, null);

        TextView titleText = dialogView.findViewById(R.id.obstacleIdText);
        titleText.setText("Add New Obstacle");

        EditText widthInput = dialogView.findViewById(R.id.widthInput);
        EditText heightInput = dialogView.findViewById(R.id.heightInput);
        Spinner faceSpinner = dialogView.findViewById(R.id.faceSpinner);

        // Setup face spinner
        String[] directions = {"North", "South", "East", "West"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, directions);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        faceSpinner.setAdapter(adapter);

        // Set defaults
        widthInput.setText("1");
        heightInput.setText("1");

        new AlertDialog.Builder(this)
                .setTitle("Add Obstacle")
                .setView(dialogView)
                .setPositiveButton("Add", (dialog, which) -> {
                    try {
                        int width = Integer.parseInt(widthInput.getText().toString());
                        int height = Integer.parseInt(heightInput.getText().toString());
                        String selectedFace = (String) faceSpinner.getSelectedItem();

                        // Validate dimensions
                        width = Math.max(1, Math.min(width, arenaMapView.getGridSize()));
                        height = Math.max(1, Math.min(height, arenaMapView.getGridSize()));

                        // Create obstacle at center of grid
                        int gridX = (arenaMapView.getGridSize() - width) / 2;
                        int gridY = (arenaMapView.getGridSize() - height) / 2;

                        Obstacle obstacle = new Obstacle(gridX, gridY, width, height);
                        obstacle.setTargetFace(Obstacle.Direction.fromDisplayName(selectedFace));

                        arenaMapView.addObstacle(obstacle);
                        arenaMapView.setSelectedObstacle(obstacle);

                        Toast.makeText(this, "Obstacle added. Drag to position.", Toast.LENGTH_SHORT).show();
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "Invalid dimensions", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Show dialog to edit an existing obstacle
     */
    private void showEditObstacleDialog(Obstacle obstacle) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_obstacle, null);

        TextView titleText = dialogView.findViewById(R.id.obstacleIdText);
        titleText.setText("Obstacle #" + obstacle.getId());

        EditText widthInput = dialogView.findViewById(R.id.widthInput);
        EditText heightInput = dialogView.findViewById(R.id.heightInput);
        Spinner faceSpinner = dialogView.findViewById(R.id.faceSpinner);

        // Setup face spinner
        String[] directions = {"North", "South", "East", "West"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, directions);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        faceSpinner.setAdapter(adapter);

        // Populate current values
        widthInput.setText(String.valueOf(obstacle.getWidth()));
        heightInput.setText(String.valueOf(obstacle.getHeight()));

        // Set current face selection
        for (int i = 0; i < directions.length; i++) {
            if (directions[i].equals(obstacle.getTargetFace().getDisplayName())) {
                faceSpinner.setSelection(i);
                break;
            }
        }

        new AlertDialog.Builder(this)
                .setTitle("Edit Obstacle")
                .setView(dialogView)
                .setPositiveButton("Save", (dialog, which) -> {
                    try {
                        int width = Integer.parseInt(widthInput.getText().toString());
                        int height = Integer.parseInt(heightInput.getText().toString());
                        String selectedFace = (String) faceSpinner.getSelectedItem();

                        // Validate dimensions
                        width = Math.max(1, Math.min(width, arenaMapView.getGridSize()));
                        height = Math.max(1, Math.min(height, arenaMapView.getGridSize()));

                        // Update obstacle
                        obstacle.setWidth(width);
                        obstacle.setHeight(height);
                        obstacle.setTargetFace(Obstacle.Direction.fromDisplayName(selectedFace));

                        // Ensure obstacle stays within grid bounds after resize
                        if (obstacle.getGridX() + width > arenaMapView.getGridSize()) {
                            obstacle.setGridX(arenaMapView.getGridSize() - width);
                        }
                        if (obstacle.getGridY() + height > arenaMapView.getGridSize()) {
                            obstacle.setGridY(arenaMapView.getGridSize() - height);
                        }

                        arenaMapView.updateObstacle(obstacle);
                        Toast.makeText(this, "Obstacle updated", Toast.LENGTH_SHORT).show();
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "Invalid dimensions", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ============================================================
    // ArenaMapView.OnObstacleActionListener implementation
    // ============================================================

    @Override
    public void onObstacleLongPress(Obstacle obstacle) {
        showEditObstacleDialog(obstacle);
    }

    @Override
    public void onObstacleSelected(Obstacle obstacle) {
        Log.d(TAG, "Selected obstacle: " + obstacle);
    }

    @Override
    public void onObstaclePositionChanged(Obstacle obstacle) {
        Log.d(TAG, "Obstacle moved: " + obstacle);
        // Optionally send real-time updates when obstacles are dragged
        // Uncomment the line below to enable automatic updates on drag:
        // sendObstacleUpdate(obstacle);
    }

    @Override
    public void onRobotPositionChanged(Robot robot) {
        Log.d(TAG, "Robot moved: " + robot);
    }

    @Override
    public void onEmptyCellTap(int gridX, int gridY) {
        Log.d(TAG, "Empty cell tapped: (" + gridX + ", " + gridY + ")");
    }

    // ============================================================
    // BLUETOOTH CONNECTION MANAGEMENT
    // ============================================================

    /**
     * Auto-start listening when app starts
     */
    private void startListeningOnStartup() {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Bluetooth permission not granted yet");
                    return;
                }
            }

            if (!isConnected) {
                bluetoothService.startServer();
                statusText.setText("Status: Ready");
                Log.d(TAG, "Started listening for incoming connections");
            }
        }, 500);
    }

    /**
     * Handler for messages from BluetoothService
     */
    private final Handler messageHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(@NonNull Message msg) {
            switch (msg.what) {
                case BluetoothService.MESSAGE_READ:
                    String receivedMessage = (String) msg.obj;
                    appendReceivedText("Received: " + receivedMessage);
                    // Parse incoming messages for TARGET commands (C.9)
                    parseIncomingMessage(receivedMessage);
                    break;

                case BluetoothService.MESSAGE_WRITE:
                    String sentMessage = (String) msg.obj;
                    appendReceivedText("Sent: " + sentMessage);
                    break;

                case BluetoothService.MESSAGE_DISCONNECTED:
                    disconnect();
                    Toast.makeText(MainActivity.this, "Device disconnected", Toast.LENGTH_SHORT).show();
                    bluetoothService.restartServer();
                    statusText.setText("Status: Ready");
                    break;

                case BluetoothService.MESSAGE_CONNECTED:
                    isConnected = true;
                    statusText.setText("Status: Connected");
                    connectButton.setText("Disconnect");
                    connectButton.setEnabled(true);
                    sendButton.setEnabled(true);
                    receivedText.setText("");
                    Toast.makeText(MainActivity.this, "Device connected!", Toast.LENGTH_SHORT).show();
                    break;
            }
        }
    };

    // ============================================================
    // C.9: PARSE INCOMING TARGET MESSAGES
    // ============================================================

    /**
     * Parse incoming Bluetooth messages and handle commands.
     * Currently supports:
     *   - TARGET, <Obstacle Number>, <Target ID>  (C.9)
     *   - ROBOT, <x>, <y>, <direction>            (C.10)
     */
    private void parseIncomingMessage(String message) {
        if (message == null || message.isEmpty()) {
            return;
        }

        // Trim whitespace and handle different line endings
        message = message.trim();

        String upperMessage = message.toUpperCase();

        // Check for TARGET command: "TARGET, <Obstacle Number>, <Target ID>"
        if (upperMessage.startsWith("TARGET")) {
            handleTargetMessage(message);
        }
        // Check for ROBOT command: "ROBOT, <x>, <y>, <direction>"
        else if (upperMessage.startsWith("ROBOT")) {
            handleRobotMessage(message);
        }
    }

    /**
     * Handle TARGET message from robot.
     * Format: TARGET, <Obstacle Number>, <Target ID>
     * Example: "TARGET, 2, A" means Obstacle #2 has target image "A"
     */
    private void handleTargetMessage(String message) {
        try {
            // Split by comma and trim each part
            String[] parts = message.split(",");
            if (parts.length < 3) {
                Log.w(TAG, "Invalid TARGET message format: " + message);
                return;
            }

            // Parse obstacle number (second part)
            int obstacleNumber = Integer.parseInt(parts[1].trim());

            // Parse target ID (third part)
            String targetId = parts[2].trim();

            // Find and update the obstacle
            Obstacle obstacle = findObstacleById(obstacleNumber);
            if (obstacle != null) {
                obstacle.setRecognizedTargetId(targetId);
                arenaMapView.updateObstacle(obstacle);
                Toast.makeText(this, "Target " + targetId + " identified on Obstacle #" + obstacleNumber,
                        Toast.LENGTH_SHORT).show();
                Log.d(TAG, "Updated Obstacle #" + obstacleNumber + " with Target ID: " + targetId);
            } else {
                Log.w(TAG, "Obstacle #" + obstacleNumber + " not found");
                Toast.makeText(this, "Obstacle #" + obstacleNumber + " not found", Toast.LENGTH_SHORT).show();
            }

        } catch (NumberFormatException e) {
            Log.e(TAG, "Failed to parse TARGET message: " + message, e);
        }
    }

    /**
     * Find an obstacle by its ID number
     */
    private Obstacle findObstacleById(int id) {
        for (Obstacle obs : arenaMapView.getObstacles()) {
            if (obs.getId() == id) {
                return obs;
            }
        }
        return null;
    }

    // ============================================================
    // C.10: PARSE INCOMING ROBOT POSITION UPDATES
    // ============================================================

    /**
     * Handle ROBOT message from robot (autonomous exploration updates).
     * Format: ROBOT, <x>, <y>, <direction>
     * Where:
     *   <x>, <y> = Grid coordinates (0-19)
     *   <direction> = N, S, E, or W
     * Example: "ROBOT, 5, 10, N" means robot is at (5,10) facing North
     */
    private void handleRobotMessage(String message) {
        try {
            // Split by comma and trim each part
            String[] parts = message.split(",");
            if (parts.length < 4) {
                Log.w(TAG, "Invalid ROBOT message format: " + message);
                return;
            }

            // Parse x coordinate (second part)
            int x = Integer.parseInt(parts[1].trim());

            // Parse y coordinate (third part)
            int y = Integer.parseInt(parts[2].trim());

            // Parse direction (fourth part)
            String directionStr = parts[3].trim().toUpperCase();
            Robot.Direction direction = Robot.Direction.fromCode(directionStr);

            // Validate coordinates (must be within grid bounds for 3x3 robot)
            int gridSize = arenaMapView.getGridSize();
            if (x < 0 || x > gridSize - Robot.SIZE || y < 0 || y > gridSize - Robot.SIZE) {
                Log.w(TAG, "ROBOT coordinates out of bounds: (" + x + ", " + y + ")");
                Toast.makeText(this, "Robot position out of bounds", Toast.LENGTH_SHORT).show();
                return;
            }

            // Update or create robot
            arenaMapView.updateRobotPosition(x, y, direction);

            Log.d(TAG, "Robot updated: position=(" + x + ", " + y + "), facing=" + direction);

        } catch (NumberFormatException e) {
            Log.e(TAG, "Failed to parse ROBOT message: " + message, e);
        }
    }

    /**
     * Check and request Bluetooth permissions
     */
    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{
                                Manifest.permission.BLUETOOTH_CONNECT,
                                Manifest.permission.BLUETOOTH_SCAN
                        },
                        REQUEST_BLUETOOTH_PERMISSIONS);
            }
        }
    }

    /**
     * Check permissions and initiate connection
     */
    private void checkPermissionsAndConnect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.BLUETOOTH_CONNECT},
                        REQUEST_BLUETOOTH_PERMISSIONS);
                return;
            }
        }
        showDeviceList();
    }

    /**
     * Show list of paired devices
     */
    private void showDeviceList() {
        try {
            Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();

            if (pairedDevices.isEmpty()) {
                Toast.makeText(this, "No paired devices found. Please pair your device first.",
                        Toast.LENGTH_LONG).show();
                return;
            }

            final ArrayList<BluetoothDevice> deviceList = new ArrayList<>(pairedDevices);
            String[] deviceNames = new String[deviceList.size()];

            for (int i = 0; i < deviceList.size(); i++) {
                deviceNames[i] = deviceList.get(i).getName() + "\n" + deviceList.get(i).getAddress();
            }

            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Select Device")
                    .setItems(deviceNames, (dialog, which) -> {
                        connectToDevice(deviceList.get(which));
                    })
                    .setNegativeButton("Cancel", null)
                    .show();

        } catch (SecurityException e) {
            Log.e(TAG, "Permission denied", e);
            Toast.makeText(this, "Bluetooth permission required", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Connect to the selected device
     */
    private void connectToDevice(final BluetoothDevice device) {
        statusText.setText("Status: Connecting...");
        connectButton.setEnabled(false);

        new Thread(() -> {
            try {
                BluetoothSocket socket = device.createRfcommSocketToServiceRecord(MY_UUID);
                bluetoothAdapter.cancelDiscovery();
                socket.connect();

                runOnUiThread(() -> {
                    bluetoothService.connect(socket);
                    isConnected = true;
                    statusText.setText("Status: Connected to " + device.getName());
                    connectButton.setText("Disconnect");
                    connectButton.setEnabled(true);
                    sendButton.setEnabled(true);
                    receivedText.setText("");
                    Toast.makeText(MainActivity.this, "Connected!", Toast.LENGTH_SHORT).show();
                });

            } catch (IOException e) {
                Log.e(TAG, "Connection failed", e);
                runOnUiThread(() -> {
                    statusText.setText("Status: Connection Failed");
                    connectButton.setEnabled(true);
                    Toast.makeText(MainActivity.this, "Connection failed: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            } catch (SecurityException e) {
                Log.e(TAG, "Permission denied", e);
                runOnUiThread(() -> {
                    statusText.setText("Status: Permission Denied");
                    connectButton.setEnabled(true);
                });
            }
        }).start();
    }

    /**
     * Disconnect from device
     */
    private void disconnect() {
        bluetoothService.stop();
        isConnected = false;
        statusText.setText("Status: Ready");
        connectButton.setText("Connect to Device");
        sendButton.setEnabled(false);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            bluetoothService.startServer();
        }, 1000);
    }

    /**
     * Send message via Bluetooth
     */
    private void sendMessage(String message) {
        if (isConnected) {
            bluetoothService.write(message + "\n");
        } else {
            Log.d(TAG, "Cannot send - not connected: " + message);
        }
    }

    /**
     * Append text to received messages view
     */
    private void appendReceivedText(String text) {
        String currentText = receivedText.getText().toString();
        if (currentText.equals("Waiting for data...")) {
            receivedText.setText(text);
        } else {
            receivedText.setText(currentText + "\n" + text);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Bluetooth permission granted", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Bluetooth permission is required for this app",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bluetoothService != null) {
            bluetoothService.stop();
        }
    }
}
