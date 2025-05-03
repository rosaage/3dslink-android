package com.rosaage.android3dslink;

import androidx.appcompat.app.AppCompatActivity;
import androidx.annotation.Nullable;

import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.net.Socket;
import java.util.Locale;
import java.util.zip.Deflater;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class MainActivity extends AppCompatActivity {

    private static final int FILE_SELECT_CODE = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button buttonSend = findViewById(R.id.button_send);
        buttonSend.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                EditText editText = findViewById(R.id.filename);
                String fileName = editText.getText().toString();

                EditText ipAddressEditText = findViewById(R.id.ipAddress);
                String ipAddress = ipAddressEditText.getText().toString();

                if (ipAddress.isEmpty()) {
                    Toast.makeText(MainActivity.this, "Please enter an IP address", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!ipAddress.matches("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")) {
                    Toast.makeText(MainActivity.this, "Invalid IP address format", Toast.LENGTH_SHORT).show();
                    return;
                }

                if (fileName.isEmpty()) {
                    Toast.makeText(MainActivity.this, "Please select a file first", Toast.LENGTH_SHORT).show();
                    return;
                }

                Uri fileUri = Uri.parse(fileName);
                sendFile(fileUri, ipAddress);
            }
        });

        Button buttonSelectFile = findViewById(R.id.button_select);
        buttonSelectFile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openFileSelector();
            }
        });

        Button buttonAbout = findViewById(R.id.buttonAbout);
        buttonAbout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(MainActivity.this, AboutActivity.class);
                startActivity(intent);
            }
        });
    }

    private void sendFile(Uri fileUri, String ipAddress) {
        new Thread(() -> {
            try {
                // Define the port and create a socket
                final int NETLOADER_COMM_PORT = 17491;
                Socket socket = new Socket(ipAddress, NETLOADER_COMM_PORT);

                // Open the file input stream from the Uri
                ContentResolver contentResolver = getContentResolver();
                InputStream inputStream = contentResolver.openInputStream(fileUri);
                if (inputStream == null) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Unable to open file", Toast.LENGTH_SHORT).show());
                    inputStream.close();
                    socket.close();
                    return;
                }

                // Get filename
                TextView filenameView = findViewById(R.id.filenameView);
                String fileName = filenameView.getText().toString();
                if (fileName == null) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Unable to retrieve file name", Toast.LENGTH_SHORT).show());
                    inputStream.close();
                    socket.close();
                    return;
                }

                // Create data streams for sending and receiving data
                DataOutputStream dataOutputStream = new DataOutputStream(socket.getOutputStream());
                DataInputStream dataInputStream = new DataInputStream(socket.getInputStream());

                // Send the file name length and name
                byte[] fileNameBytes = fileName.getBytes("UTF-8");
                dataOutputStream.write(toLittleEndian(fileNameBytes.length)); // Send the length of the UTF-8 encoded file name
                dataOutputStream.write(fileNameBytes); // Send the UTF-8 encoded file name

                // Send the file size
                // Better to re-read this from the uri to get the proper int value.
                int fileSize = getFileSize(fileUri);
                //runOnUiThread(() -> Toast.makeText(MainActivity.this, "name: " + fileName + " len: " + fileName.length() + " size: " + fileSize, Toast.LENGTH_SHORT).show());
                if (fileSize == -1) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Unable to retrieve file size", Toast.LENGTH_SHORT).show());
                    inputStream.close();
                    dataOutputStream.close();
                    socket.close();
                    return;
                }
                dataOutputStream.write(toLittleEndian(fileSize));

                // Wait for a response from the server
                int response = dataInputStream.readInt(); // Should be LE, but we don't care
                if (response != 0) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Server rejected the file", Toast.LENGTH_SHORT).show());
                    inputStream.close();
                    dataOutputStream.close();
                    dataInputStream.close();
                    socket.close();
                    return;
                }

                // Read the file, compress it with zlib, and send its contents in chunks
                byte[] buffer = new byte[16 * 1024]; // 16 KB buffer
                byte[] compressedBuffer = new byte[16 * 1024]; // Buffer for compressed data
                Deflater deflater = new Deflater();
                int bytesRead;

                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    deflater.setInput(buffer, 0, bytesRead);

                    // Compress data until the Deflater needs more input
                    while (!deflater.needsInput()) {
                        int compressedBytes = deflater.deflate(compressedBuffer);
                        if (compressedBytes > 0) {
                            dataOutputStream.write(toLittleEndian(compressedBytes)); // Send the size of the compressed chunk
                            dataOutputStream.write(compressedBuffer, 0, compressedBytes); // Send the compressed chunk
                        }
                    }
                }

                // Finish compression and send remaining data
                deflater.finish();
                while (!deflater.finished()) {
                    int compressedBytes = deflater.deflate(compressedBuffer);
                    if (compressedBytes > 0) {
                        dataOutputStream.write(toLittleEndian(compressedBytes)); // Send the size of the compressed chunk
                        dataOutputStream.write(compressedBuffer, 0, compressedBytes); // Send the compressed chunk
                    }
                }

                deflater.end();

                // Wait for a response from the server
                response = dataInputStream.readInt(); // Should be LE, but we don't care
                if (response != 0) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Server rejected the file", Toast.LENGTH_SHORT).show());
                    inputStream.close();
                    dataOutputStream.close();
                    dataInputStream.close();
                    socket.close();
                    return;
                }

                // Send the final command
                String hardcodedPath = "sdmc:/3ds/" + fileName;
                byte[] hardcodedPathBytes = fileName.getBytes("UTF-8");
                dataOutputStream.write(toLittleEndian(hardcodedPathBytes.length)); // Send the length of the UTF-8 encoded file name
                dataOutputStream.write(hardcodedPathBytes); // Send the UTF-8 encoded file name

                // Close streams and socket
                inputStream.close();
                dataInputStream.close();
                dataOutputStream.close();
                socket.close();

                // Notify the user of success
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "File sent successfully", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Error sending file: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void openFileSelector() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*"); // Allow all file types
        intent.addCategory(Intent.CATEGORY_OPENABLE);

        try {
            startActivityForResult(Intent.createChooser(intent, "Select a file"), FILE_SELECT_CODE);
        } catch (android.content.ActivityNotFoundException ex) {
            Toast.makeText(this, "Please install a file manager.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == FILE_SELECT_CODE && resultCode == RESULT_OK) {
            if (data != null) {
                Uri uri = data.getData();
                Toast.makeText(this, "File Selected: " + uri.getPath(), Toast.LENGTH_LONG).show();

                EditText filenameEdit = findViewById(R.id.filename);
                filenameEdit.setText(uri.toString());

                // Get the file name from the Uri
                TextView filenameView = findViewById(R.id.filenameView);
                String fileName = getFileName(uri);
                filenameView.setText(fileName);

                // Get the filesize
                TextView fileSizeView = findViewById(R.id.fileSizeView);
                int fileSize = getFileSize(uri);
                fileSizeView.setText(String.format(Locale.US, "%d", fileSize));
            }
        }
    }

    private String getFileName(Uri uri) {
        String result = null;
        if (uri.getScheme().equals("content")) {
            Cursor cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null) {
                try {
                    if (cursor.moveToFirst()) {
                        result = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME));
                    }
                } finally {
                    cursor.close();
                }
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1) {
                result = result.substring(cut + 1);
            }
        }
        return result;
    }

    private int getFileSize(Uri uri) {
        long size = -1;
        if (uri.getScheme().equals("content")) {
            Cursor cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null) {
                try {
                    if (cursor.moveToFirst()) {
                        size = cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE));
                        if (size > Integer.MAX_VALUE) {
                            size = -1; // Return -1 if the size exceeds 4 bytes (int limit)
                        }
                    }
                } finally {
                    cursor.close();
                }
            }
        }
        return (int) size;
    }

    private byte[] toLittleEndian(int value) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }
}