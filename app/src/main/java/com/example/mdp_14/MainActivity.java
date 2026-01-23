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
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int REQUEST_BLUETOOTH_PERMISSIONS = 1;

    // Standard SerialPortService ID
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private TextView statusText;
    private TextView receivedText;
    private EditText messageInput;
    private Button connectButton;
    private ImageButton sendButton;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothService bluetoothService;
    private boolean isConnected = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize UI elements
        statusText = findViewById(R.id.statusText);
        receivedText = findViewById(R.id.receivedText);
        messageInput = findViewById(R.id.messageInput);
        connectButton = findViewById(R.id.connectButton);
        sendButton = findViewById(R.id.sendButton);

        // Get Bluetooth adapter
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not available on this device", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // Initialize Bluetooth service
        bluetoothService = new BluetoothService(messageHandler, bluetoothAdapter);

        // Set up button listeners
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

        // Check permissions on startup
        checkPermissions();

        // Auto-start listening for incoming connections
        startListeningOnStartup();
    }

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
                    break;

                case BluetoothService.MESSAGE_WRITE:
                    String sentMessage = (String) msg.obj;
                    appendReceivedText("Sent: " + sentMessage);
                    break;

                case BluetoothService.MESSAGE_DISCONNECTED:
                    disconnect();
                    Toast.makeText(MainActivity.this, "Device disconnected", Toast.LENGTH_SHORT).show();
                    // Restart listening after disconnect
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

    /**
     * Check and request Bluetooth permissions
     */
    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+
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

        // Restart listening after manual disconnect
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            bluetoothService.startServer();
        }, 1000);
    }

    /**
     * Send message via Bluetooth
     */
    private void sendMessage(String message) {
        if (isConnected) {
            bluetoothService.write(message + "\n"); // Add newline for easier parsing
        } else {
            Toast.makeText(this, "Not connected to any device", Toast.LENGTH_SHORT).show();
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