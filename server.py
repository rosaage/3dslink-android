#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Simple test server for the 3dslink protocol.

Based on and tested with the 3dslink client:
https://github.com/devkitPro/3dslink
"""

import socket
import struct
import zlib
from tqdm import tqdm

# Constants
NETLOADER_COMM_PORT = 17491
ZLIB_CHUNK = 16 * 1024

def recv_exactly(sock:socket.socket, num_bytes):
    data = b""
    sock.settimeout(5)  # Set a timeout of 10 seconds
    try:
        while len(data) < num_bytes:
            packet = sock.recv(num_bytes - len(data))
            if not packet:
                raise ConnectionError("Socket connection closed unexpectedly")
            data += packet
    except socket.timeout:
        print("Socket operation timed out.")
        raise ConnectionError("Socket operation timed out.")
    finally:
        sock.settimeout(None)  # Reset timeout to None (blocking mode)
    return data

def main():
    # Create a server socket
    server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server_socket.bind(('0.0.0.0', NETLOADER_COMM_PORT))
    server_socket.listen(1)
    print(f"Server listening on port {NETLOADER_COMM_PORT}...")

    # Accept a connection with timeout handling
    server_socket.settimeout(1)  # Set a timeout of 1 second for the accept call
    while True:
        try:
            conn, addr = server_socket.accept()
            print(f"Connection established with {addr}")
            break
        except socket.timeout:
            continue  # Retry accepting connections
        except KeyboardInterrupt:
            print("\nServer shutting down.")
            server_socket.close()
            exit(0)

    final_response = -1

    try:
        # Read filename length
        filename_len_data = conn.recv(4) #recv_exactly(conn, 4)
        print(len(filename_len_data), filename_len_data)
        filename_len = struct.unpack('<I', filename_len_data)[0]
        print(f"Filename length: {filename_len}")

        # Read filename
        filename = recv_exactly(conn, filename_len).decode('utf-8')
        print(f"Filename: {filename}")

        # Read file length
        file_len_data = recv_exactly(conn, 4)
        file_len = struct.unpack('<I', file_len_data)[0]
        print(f"File length: {file_len}")

        # Send a response (0 for success)
        conn.sendall(struct.pack('<I', 0))

        # Prepare for decompression
        decompressor = zlib.decompressobj()
        total_received = 0

        # Initialize progress bar
        progress_bar = tqdm(total=file_len, unit='B', unit_scale=True, desc="Receiving")

        while True:
            # Read chunk size
            chunk_size_data = recv_exactly(conn, 4)
            if not chunk_size_data:
                break
            chunk_size = struct.unpack('<I', chunk_size_data)[0]
            #print(f"Chunk size: {chunk_size}")

            # Read chunk data
            chunk_data = recv_exactly(conn, chunk_size)
            if not chunk_data:
                break
            #print(f"Received chunk of size: {len(chunk_data)}")

            # Decompress the chunk
            decompressed_data = decompressor.decompress(chunk_data)
            with open(filename, 'ab') as f:
                f.write(decompressed_data)
            total_received += len(decompressed_data)
            #print(f"Decompressed {len(decompressed_data)} bytes, total received: {total_received}")

            # Update progress bar
            progress_bar.update(len(decompressed_data))

            # Check if the stream has ended
            if decompressor.eof:
                #print("End of zlib stream reached.")
                break

        # Close the progress bar
        progress_bar.close()
        

        # Ensure all data is decompressed
        remaining_data = decompressor.flush()
        total_received += len(remaining_data)
        print(f"Flushed {len(remaining_data)} bytes, total received: {total_received}")

        print(f"File transfer complete. Total decompressed size: {total_received} bytes.")
        final_response = 0
    finally:
        # Send a response (0 for success)
        conn.sendall(struct.pack('<I', final_response))

        conn.close()
        server_socket.close()
        print("Server closed.")

if __name__ == "__main__":
    main()