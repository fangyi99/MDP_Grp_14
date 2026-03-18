package com.example.mdp_14;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONException;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Main Activity - coordinates all app components
 * Delegates specific responsibilities to manager classes
 */
public class MainActivity extends AppCompatActivity
        implements SensorEventListener,
        ArenaMapView.OnObstacleActionListener,
        BluetoothManager.BluetoothCallback,
        MessageParser.MessageCallback,
        RobotController.RobotControllerCallback,
        RobotController.CommandSender {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_BLUETOOTH_PERMISSIONS = 1;

    // ============================================================
    // MANAGER CLASSES
    // ============================================================
    private BluetoothManager bluetoothManager;
    private MessageParser messageParser;
    private RobotController robotController;
    private ObstacleManager obstacleManager;
    private UIManager uiManager;
    private TiltController tiltController;

    // ============================================================
    // UI ELEMENTS
    // ============================================================
    private MenuItem deviceNameMenuItem;
    private Button connectButton;

    // Status displays
    private TextView robotStatusText;
    private TextView positionText;
    private TextView directionText;
    private TextView timerText;
    private TextView receivedText;
    private EditText messageInput;
    private ImageButton sendButton;
    private Button clearMessagesButton;

    // D-Pad controls
    private Button upButton, downButton, leftButton, rightButton;
    private SwitchCompat tiltControlSwitch;

    // Arena controls
    private ArenaMapView arenaMapView;
    private Button addObstacleButton, editObstacleButton, deleteObstacleButton;
    private Button clearAllButton, spawnRobotButton, sendObstaclesButton;
    private Button resetButton, exploreButton, fastestPathButton;
    private ToggleButton lockToggle;

    // ============================================================
    // LIFECYCLE
    // ============================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        restoreThemePreference();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initializeViews();
        initializeManagers();
        setupListeners();
        checkPermissions();

        // Start listening for incoming connections
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            bluetoothManager.startListening();
        }, 500);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        bluetoothManager.cleanup();
        robotController.cleanup();
        if (tiltController != null) {
            tiltController.disable();
        }
    }

    // ============================================================
    // INITIALIZATION
    // ============================================================

    private void initializeViews() {
        // Status displays
        robotStatusText = findViewById(R.id.robotStatusTxt);
        positionText = findViewById(R.id.positionTxt);
        directionText = findViewById(R.id.directionTxt);
        timerText = findViewById(R.id.timerTxt);
        receivedText = findViewById(R.id.receivedText);
        messageInput = findViewById(R.id.messageInput);
        sendButton = findViewById(R.id.sendBtn);
        clearMessagesButton = findViewById(R.id.clearMessagesBtn);

        // D-Pad controls
        upButton = findViewById(R.id.upBtn);
        downButton = findViewById(R.id.downBtn);
        leftButton = findViewById(R.id.leftBtn);
        rightButton = findViewById(R.id.rightBtn);
        tiltControlSwitch = findViewById(R.id.tiltControlSwitch);

        // Arena controls
        arenaMapView = findViewById(R.id.arenaMapView);
        addObstacleButton = findViewById(R.id.addObstacleButton);
        editObstacleButton = findViewById(R.id.editObstacleButton);
        deleteObstacleButton = findViewById(R.id.deleteObstacleButton);
        clearAllButton = findViewById(R.id.clearAllButton);
        spawnRobotButton = findViewById(R.id.spawnRobotButton);
        sendObstaclesButton = findViewById(R.id.sendObstaclesButton);
        resetButton = findViewById(R.id.resetButton);
        exploreButton = findViewById(R.id.exploreButton);
        fastestPathButton = findViewById(R.id.fastestPathButton);
        lockToggle = findViewById(R.id.lockToggle);
    }

    private void initializeManagers() {
        // Bluetooth
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not available", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        bluetoothManager = new BluetoothManager(this, bluetoothAdapter);
        bluetoothManager.setCallback(this);

        // Message parsing
        messageParser = new MessageParser(this);

        // Robot control
        robotController = new RobotController(timerText, this);
        robotController.setCallback(this);

        // Obstacles
        obstacleManager = new ObstacleManager(arenaMapView);

        // UI
        uiManager = new UIManager(this, exploreButton, fastestPathButton,
                deleteObstacleButton, clearAllButton, resetButton, connectButton,
                robotStatusText, positionText, directionText);
        uiManager.applyColorBlindMode();

        // Tilt control
        SensorManager sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        Sensor accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        tiltController = new TiltController(sensorManager, accelerometer, this);
    }

    private void setupListeners() {
        setupDPadControls();
        setupArenaControls();
        arenaMapView.setOnObstacleActionListener(this);
    }

    // ============================================================
    // D-PAD CONTROLS
    // ============================================================

    private void setupDPadControls() {
        upButton.setOnClickListener(v -> sendCommand("move:up"));
        downButton.setOnClickListener(v -> sendCommand("move:down"));
        leftButton.setOnClickListener(v -> sendCommand("move:left"));
        rightButton.setOnClickListener(v -> sendCommand("move:right"));

        sendButton.setOnClickListener(v -> {
            String message = messageInput.getText().toString().trim();
            if (!message.isEmpty()) {
                sendCommand(message);
                messageInput.setText("");
            } else {
                Toast.makeText(this, "Please enter a message", Toast.LENGTH_SHORT).show();
            }
        });

        tiltControlSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked && bluetoothManager.isConnected()) {
                tiltController.enable();
                setDPadButtonsEnabled(false);
            } else {
                tiltController.disable();
                if (bluetoothManager.isConnected()) {
                    setDPadButtonsEnabled(true);
                }
            }
        });

        clearMessagesButton.setOnClickListener(v -> clearMessages());
    }

    private void setDPadButtonsEnabled(boolean enabled) {
        upButton.setEnabled(enabled);
        downButton.setEnabled(enabled);
        leftButton.setEnabled(enabled);
        rightButton.setEnabled(enabled);
    }

    // ============================================================
    // ARENA CONTROLS
    // ============================================================

    private void setupArenaControls() {
        lockToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            arenaMapView.setDragLocked(isChecked);
            String message = isChecked ? "Map locked" : "Map unlocked";
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        });

        spawnRobotButton.setOnClickListener(v -> handleSpawnRobot());
        sendObstaclesButton.setOnClickListener(v -> handleSendObstacles());
        resetButton.setOnClickListener(v -> handleReset());
        exploreButton.setOnClickListener(v -> handleExplore());
        fastestPathButton.setOnClickListener(v -> handleFastestPath());
        addObstacleButton.setOnClickListener(v -> showAddObstacleDialog());
        editObstacleButton.setOnClickListener(v -> handleEditObstacle());
        deleteObstacleButton.setOnClickListener(v -> handleDeleteObstacle());
        clearAllButton.setOnClickListener(v -> handleClearAll());
    }

    private void handleSpawnRobot() {
        if (arenaMapView.hasRobot()) {
            showRobotOptionsDialog();
        } else {
            arenaMapView.spawnRobot();
            uiManager.updateRobotInfo(arenaMapView.getRobot());
            Toast.makeText(this, "Robot spawned. Drag to position.", Toast.LENGTH_SHORT).show();
        }
    }

    private void showRobotOptionsDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Robot")
                .setMessage("Robot already exists. What would you like to do?")
                .setPositiveButton("Reset Position", (dialog, which) -> {
                    arenaMapView.spawnRobot();
                    uiManager.updateRobotInfo(arenaMapView.getRobot());
                    Toast.makeText(this, "Robot reset", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Remove", (dialog, which) -> {
                    arenaMapView.removeRobot();
                    uiManager.clearRobotPosition();
                    Toast.makeText(this, "Robot removed", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("Cancel", null)
                .show();
    }

    private void handleSendObstacles() {
        if (!obstacleManager.hasObstacles()) {
            Toast.makeText(this, "No obstacles on the map", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            showSimpleObstacleList();
            String json = obstacleManager.buildObstaclesJSON().toString();
            sendCommand(json);
        } catch (JSONException e) {
            Log.e(TAG, "Error creating JSON", e);
            Toast.makeText(this, "Error creating JSON", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Show obstacle list in monospace code style
     */
    private void showSimpleObstacleList() {
        List<Obstacle> obstacles = arenaMapView.getObstacles();

        if (obstacles.isEmpty()) {
            return; // Already handled in handleSendObstacles
        }

        // Build simple text list
        StringBuilder message = new StringBuilder();
        message.append("Total: ").append(obstacles.size()).append(" obstacles\n");
        message.append("────────────────────────\n\n");

        for (Obstacle obs : obstacles) {
            message.append("ID:       ").append(obs.getId()).append("\n");
            message.append("Position: (").append(obs.getGridX()).append(", ").append(obs.getGridY()).append(")\n");
            message.append("Facing:   ").append(obs.getTargetFace().getDisplayName()).append("\n");
            message.append("\n");
        }

        // Create TextView with monospace font
        TextView textView = new TextView(this);
        textView.setText(message.toString());
        textView.setTypeface(android.graphics.Typeface.MONOSPACE);
        textView.setTextColor(0xFF37474F); // Dark grey code-like color
        textView.setPadding(40, 40, 40, 40);
        textView.setTextSize(14);

        // Show dialog
        new AlertDialog.Builder(this)
                .setTitle("Obstacles Sent")
                .setView(textView)
                .setPositiveButton("OK", null)
                .show();
    }

    private void handleReset() {
        robotController.stop();
        resetAll();
        Toast.makeText(this, "Robot stopped and reset", Toast.LENGTH_SHORT).show();
    }

    private void handleExplore() {
        robotController.start();
        uiManager.setExploreButtonActive();
    }

    private void handleFastestPath() {
        robotController.start();
        uiManager.setFastestPathButtonActive();
    }

    private void handleEditObstacle() {
        Obstacle selected = arenaMapView.getSelectedObstacle();
        if (selected != null) {
            showEditObstacleDialog(selected);
        } else {
            Toast.makeText(this, "Please select an obstacle first", Toast.LENGTH_SHORT).show();
        }
    }

    private void handleDeleteObstacle() {
        Obstacle selected = arenaMapView.getSelectedObstacle();
        if (selected != null) {
            arenaMapView.removeObstacle(selected);
            sendObstacleUpdate();
            Toast.makeText(this, "Obstacle deleted", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Please select an obstacle first", Toast.LENGTH_SHORT).show();
        }
    }

    private void handleClearAll() {
        new AlertDialog.Builder(this)
                .setTitle("Clear All")
                .setMessage("Remove all obstacles and robot?")
                .setPositiveButton("Yes", (dialog, which) -> {
                    arenaMapView.clearObstacles();
                    arenaMapView.removeRobot();
                    uiManager.clearRobotPosition();
                    Toast.makeText(this, "All cleared", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("No", null)
                .show();
    }

    private void resetAll() {
        robotController.resetTimer();
        uiManager.resetAllButtons();
        obstacleManager.resetAllRecognitions();
        uiManager.applyColorBlindMode();
    }

    // ============================================================
    // OBSTACLE DIALOGS
    // ============================================================

    private void showAddObstacleDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_obstacle, null);
        TextView titleText = dialogView.findViewById(R.id.obstacleIdText);
        titleText.setText(R.string.dialog_add_obstacle);

        EditText widthInput = dialogView.findViewById(R.id.widthInput);
        EditText heightInput = dialogView.findViewById(R.id.heightInput);
        Spinner faceSpinner = dialogView.findViewById(R.id.faceSpinner);

        setupDirectionSpinner(faceSpinner);
        widthInput.setText("1");
        heightInput.setText("1");

        new AlertDialog.Builder(this)
                .setTitle("Add Obstacle")
                .setView(dialogView)
                .setPositiveButton("Add", (dialog, which) -> {
                    addObstacleFromDialog(widthInput, heightInput, faceSpinner);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showEditObstacleDialog(Obstacle obstacle) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_obstacle, null);
        TextView titleText = dialogView.findViewById(R.id.obstacleIdText);
        titleText.setText("Obstacle #" + obstacle.getId());

        EditText widthInput = dialogView.findViewById(R.id.widthInput);
        EditText heightInput = dialogView.findViewById(R.id.heightInput);
        Spinner faceSpinner = dialogView.findViewById(R.id.faceSpinner);

        setupDirectionSpinner(faceSpinner);
        widthInput.setText(String.valueOf(obstacle.getWidth()));
        heightInput.setText(String.valueOf(obstacle.getHeight()));

        String[] directions = {"North", "South", "East", "West"};
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
                    editObstacleFromDialog(obstacle, widthInput, heightInput, faceSpinner);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void setupDirectionSpinner(Spinner spinner) {
        String[] directions = {"North", "South", "East", "West"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, directions);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
    }

    private void addObstacleFromDialog(EditText widthInput, EditText heightInput, Spinner faceSpinner) {
        try {
            int width = Integer.parseInt(widthInput.getText().toString());
            int height = Integer.parseInt(heightInput.getText().toString());
            String selectedFace = (String) faceSpinner.getSelectedItem();

            width = Math.max(1, Math.min(width, arenaMapView.getGridSize()));
            height = Math.max(1, Math.min(height, arenaMapView.getGridSize()));

            int gridX = (arenaMapView.getGridSize() - width) / 2;
            int gridY = (arenaMapView.getGridSize() - height) / 2;

            Obstacle obstacle = new Obstacle(gridX, gridY, width, height);
            obstacle.setTargetFace(Obstacle.Direction.fromDisplayName(selectedFace));

            arenaMapView.addObstacle(obstacle);
            arenaMapView.setSelectedObstacle(obstacle);
            sendObstacleUpdate();

            Toast.makeText(this, "Obstacle added. Drag to position.", Toast.LENGTH_SHORT).show();
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Invalid dimensions", Toast.LENGTH_SHORT).show();
        }
    }

    private void editObstacleFromDialog(Obstacle obstacle, EditText widthInput,
                                        EditText heightInput, Spinner faceSpinner) {
        try {
            int width = Integer.parseInt(widthInput.getText().toString());
            int height = Integer.parseInt(heightInput.getText().toString());
            String selectedFace = (String) faceSpinner.getSelectedItem();

            width = Math.max(1, Math.min(width, arenaMapView.getGridSize()));
            height = Math.max(1, Math.min(height, arenaMapView.getGridSize()));

            obstacle.setWidth(width);
            obstacle.setHeight(height);
            obstacle.setTargetFace(Obstacle.Direction.fromDisplayName(selectedFace));

            if (obstacle.getGridX() + width > arenaMapView.getGridSize()) {
                obstacle.setGridX(arenaMapView.getGridSize() - width);
            }
            if (obstacle.getGridY() + height > arenaMapView.getGridSize()) {
                obstacle.setGridY(arenaMapView.getGridSize() - height);
            }

            arenaMapView.updateObstacle(obstacle);
            sendObstacleUpdate();
            Toast.makeText(this, "Obstacle updated", Toast.LENGTH_SHORT).show();
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Invalid dimensions", Toast.LENGTH_SHORT).show();
        }
    }

    private void sendObstacleUpdate() {
        try {
            String json = obstacleManager.buildObstaclesJSON().toString();
            sendCommand(json);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to send obstacle update", e);
        }
    }

    // ============================================================
    // ARENA MAP LISTENER CALLBACKS
    // ============================================================

    @Override
    public void onObstacleLongPress(Obstacle obstacle) {
        showEditObstacleDialog(obstacle);
    }

    @Override
    public void onObstacleSelected(Obstacle obstacle) {
        Log.d(TAG, "Selected: " + obstacle);
    }

    @Override
    public void onObstaclePositionChanged(Obstacle obstacle) {
        sendObstacleUpdate();
    }

    @Override
    public void onObstacleRemovedByDrag(Obstacle obstacle) {
        sendObstacleUpdate();
        Toast.makeText(this, "Obstacle #" + obstacle.getId() + " removed", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRobotPositionChanged(Robot robot) {
        uiManager.updateRobotInfo(robot);
    }

    @Override
    public void onEmptyCellTap(int gridX, int gridY) {
        Log.d(TAG, "Empty cell tapped: (" + gridX + ", " + gridY + ")");
    }

    // ============================================================
    // BLUETOOTH CALLBACKS
    // ============================================================

    @Override
    public void onConnected(String deviceName) {
        uiManager.setConnectedState(deviceName);
        setDPadButtonsEnabled(true);
        sendButton.setEnabled(true);
        tiltControlSwitch.setEnabled(true);
        updateActionBarMenuItem(deviceName);
        logMessage("Device connected: " + deviceName, "#4CAF50");
        Toast.makeText(this, "Connected!", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDisconnected() {
        uiManager.setDisconnectedState();
        setDPadButtonsEnabled(false);
        sendButton.setEnabled(false);
        tiltControlSwitch.setEnabled(false);
        tiltControlSwitch.setChecked(false);
        updateActionBarMenuItem(null);
        logMessage("Device disconnected", "#F44336");
        Toast.makeText(this, "Disconnected", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onMessageReceived(String message) {
        logMessage("Received: " + message, "#388E3C");
        messageParser.parseMessage(message);
    }

    @Override
    public void onMessageSent(String message) {
        logMessage("Sent: " + message, "#1976D2");
    }

    @Override
    public void onConnectionFailed(String error) {
        uiManager.setDisconnectedState();
        logMessage("Connection failed: " + error, "#F44336");
        Toast.makeText(this, "Connection failed", Toast.LENGTH_LONG).show();
    }

    // ============================================================
    // MESSAGE PARSER CALLBACKS
    // ============================================================

    @Override
    public void onStatusUpdate(String status) {
        uiManager.updateRobotStatus(status);

        if ("finished".equalsIgnoreCase(status)) {
            robotController.stopTimer();
            uiManager.resetAllButtons();
        }
    }

    @Override
    public void onImageRecognition(String imageId, int obstacleId) {
        String displayId = ImageIdMapper.mapImageId(imageId);

        Obstacle obstacle = obstacleManager.findObstacleById(obstacleId);
        if (obstacle != null) {
            obstacle.setRecognizedTargetId(displayId);
            arenaMapView.updateObstacle(obstacle);
            Toast.makeText(this, "Target " + displayId + " on Obstacle #" + obstacleId,
                    Toast.LENGTH_SHORT).show();
            Log.d(TAG, "Image recognized: " + displayId + " on obstacle " + obstacleId);
        } else {
            Log.w(TAG, "Obstacle #" + obstacleId + " not found");
        }
    }

    @Override
    public void onRobotLocationUpdate(int x, int y, int direction) {
        Robot.Direction facing = Robot.Direction.fromNumeric(direction);

        int gridSize = arenaMapView.getGridSize();
        if (x < 0 || x > gridSize - Robot.SIZE || y < 0 || y > gridSize - Robot.SIZE) {
            Log.w(TAG, "Robot coordinates out of bounds: (" + x + ", " + y + ")");
            return;
        }

        arenaMapView.updateRobotPosition(x, y, facing);
        uiManager.updateRobotPosition(x, y);
        uiManager.updateRobotDirection(facing.name());
    }

    // ============================================================
    // ROBOT CONTROLLER CALLBACKS
    // ============================================================

    @Override
    public void onTimerExpired() {
        uiManager.resetAllButtons();
        Toast.makeText(this, "Time's up! Robot stopped.", Toast.LENGTH_LONG).show();
    }

    @Override
    public void sendCommand(String command) {
        if (bluetoothManager.isConnected()) {
            bluetoothManager.sendMessage(command);
        } else {
            Log.d(TAG, "Cannot send - not connected: " + command);
        }
    }

    // ============================================================
    // TILT CONTROL (SENSOR LISTENER)
    // ============================================================

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (tiltController != null) {
            tiltController.onSensorChanged(event);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Not needed
    }

    // ============================================================
    // ACTION BAR & SETTINGS
    // ============================================================

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        deviceNameMenuItem = menu.findItem(R.id.deviceNameTxt);
        MenuItem item = menu.findItem(R.id.connectBtn);
        connectButton = Objects.requireNonNull(item.getActionView()).findViewById(R.id.connectBtn);

        connectButton.setOnClickListener(v -> {
            if (!bluetoothManager.isConnected()) {
                checkPermissionsAndConnect();
            } else {
                bluetoothManager.disconnect();
            }
        });

        updateActionBarMenuItem(null);
        uiManager.applyColorBlindMode();
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.settingsBtn) {
            showSettingsDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void updateActionBarMenuItem(String deviceName) {
        if (deviceNameMenuItem != null) {
            if (bluetoothManager.isConnected() && deviceName != null) {
                deviceNameMenuItem.setTitle(deviceName);
                deviceNameMenuItem.setVisible(true);
            } else {
                deviceNameMenuItem.setVisible(false);
            }
        }
    }

    private void showSettingsDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_settings, null);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(true)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        setupSettingsDialog(dialogView, dialog);

        dialog.show();
        if (dialog.getWindow() != null) {
            int width = (int)(getResources().getDisplayMetrics().widthPixels * 0.85);
            dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void setupSettingsDialog(View dialogView, AlertDialog dialog) {
        LinearLayout optionLight = dialogView.findViewById(R.id.optionLight);
        LinearLayout optionDark = dialogView.findViewById(R.id.optionDark);
        RadioButton radioLight = dialogView.findViewById(R.id.radioLight);
        RadioButton radioDark = dialogView.findViewById(R.id.radioDark);
        SwitchCompat colourBlindSwitch = dialogView.findViewById(R.id.colourBlindSwitch);
        Spinner languageSpinner = dialogView.findViewById(R.id.languageSpinner);
        Button btnClose = dialogView.findViewById(R.id.btnCloseSettings);

        SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);

        // Theme
        boolean isDark = prefs.getBoolean("dark_mode", false);
        radioLight.setChecked(!isDark);
        radioDark.setChecked(isDark);

        optionLight.setOnClickListener(v -> {
            radioLight.setChecked(true);
            radioDark.setChecked(false);
            prefs.edit().putBoolean("dark_mode", false).apply();
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
            dialog.dismiss();
        });

        optionDark.setOnClickListener(v -> {
            radioDark.setChecked(true);
            radioLight.setChecked(false);
            prefs.edit().putBoolean("dark_mode", true).apply();
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
            dialog.dismiss();
        });

        // Color Blind Mode
        colourBlindSwitch.setChecked(prefs.getBoolean("colour_blind_mode", false));
        colourBlindSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
            prefs.edit().putBoolean("colour_blind_mode", isChecked).apply();
            uiManager.applyColorBlindMode();
        });

        // Language
        setupLanguageSpinner(languageSpinner, prefs, dialog);

        btnClose.setOnClickListener(v -> dialog.dismiss());
    }

    private void setupLanguageSpinner(Spinner languageSpinner, SharedPreferences prefs, AlertDialog dialog) {
        String[] languages = {"English 英文", "Chinese 中文"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.spinner_item, languages);
        adapter.setDropDownViewResource(R.layout.spinner_item);
        languageSpinner.setAdapter(adapter);

        String savedLang = prefs.getString("language", "en");
        languageSpinner.setSelection(savedLang.equals("zh") ? 1 : 0);

        languageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String langCode = position == 1 ? "zh" : "en";
                String currentLang = prefs.getString("language", "en");

                if (!langCode.equals(currentLang)) {
                    prefs.edit().putString("language", langCode).apply();
                    Locale locale = new Locale(langCode);
                    Locale.setDefault(locale);
                    dialog.dismiss();
                    recreate();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    @Override
    protected void attachBaseContext(Context base) {
        SharedPreferences prefs = base.getSharedPreferences("settings", MODE_PRIVATE);
        String lang = prefs.getString("language", "en");
        Locale locale = new Locale(lang);
        super.attachBaseContext(LocaleContextWrapper.wrap(base, locale));
    }

    private void restoreThemePreference() {
        SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
        boolean isDark = prefs.getBoolean("dark_mode", false);
        AppCompatDelegate.setDefaultNightMode(
                isDark ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
    }

    // ============================================================
    // PERMISSIONS
    // ============================================================

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
        bluetoothManager.showDeviceSelectionDialog();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Bluetooth permission granted", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Bluetooth permission required", Toast.LENGTH_LONG).show();
            }
        }
    }

    // ============================================================
    // UTILITY METHODS
    // ============================================================

    private void logMessage(String message, String colorHex) {
        String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String currentText = receivedText.getText().toString();

        if (currentText.equals("Waiting for data...")) {
            currentText = "";
        }

        String newText = "[" + timestamp + "] " + message + "\n" + currentText;

        // Keep last 20 messages
        String[] lines = newText.split("\n");
        if (lines.length > 20) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 20; i++) {
                sb.append(lines[i]).append("\n");
            }
            newText = sb.toString();
        }

        receivedText.setText(newText);
    }

    private void clearMessages() {
        receivedText.setText("Waiting for data...");
        Toast.makeText(this, "Messages cleared", Toast.LENGTH_SHORT).show();
    }
}