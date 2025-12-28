import { Injectable, NgZone } from '@angular/core';
import { Observable, Subject } from 'rxjs';

export interface ChatMessage {
    type: 'WAITING' | 'MATCHED' | 'MESSAGE' | 'PARTNER_DISCONNECTED' | 'ERROR';
    role?: 'INITIATOR' | 'PEER';
    content?: string; // For matched/message
    partnerName?: string; // For MATCHED event
}

import { environment } from '../environments/environment';

@Injectable({
    providedIn: 'root'
})
export class ChatService {
    private socket: WebSocket | null = null;
    private messageSubject = new Subject<ChatMessage>();

    public messages$ = this.messageSubject.asObservable();

    constructor(private ngZone: NgZone) { }

    private pingInterval: any;

    connect(onConnected?: () => void): void {
        if (this.socket) {
            this.socket.close();
        }
        // Use environment variable instead of hardcoded string
        this.socket = new WebSocket(environment.wsUrl);

        this.socket.onopen = () => {
            this.ngZone.run(() => {
                console.log('WebSocket Connected to:', environment.wsUrl);
                if (onConnected) onConnected();

                // Start Heartbeat: Ping every 30s
                this.stopHeartbeat();
                this.pingInterval = setInterval(() => {
                    if (this.socket && this.socket.readyState === WebSocket.OPEN) {
                        this.socket.send(JSON.stringify({ type: 'PING' }));
                    }
                }, 30000);
            });
        };

        this.socket.onmessage = (event) => {
            console.log('WebSocket Received:', event.data);
            try {
                const data = JSON.parse(event.data);
                if (data.type === 'PONG') {
                    return; // Ignore PONG in UI
                }

                this.ngZone.run(() => {
                    this.messageSubject.next(data);
                });
            } catch (e) {
                console.error('Failed to parse message', event.data);
            }
        };

        this.socket.onclose = (event) => {
            this.ngZone.run(() => {
                console.warn('WebSocket Closed:', event);
                this.stopHeartbeat();
            });
        };

        this.socket.onerror = (error) => {
            this.ngZone.run(() => {
                console.error('WebSocket Error:', error);
                this.stopHeartbeat();
            });
        };
    }

    private stopHeartbeat() {
        if (this.pingInterval) {
            clearInterval(this.pingInterval);
            this.pingInterval = null;
        }
    }

    sendJoin(name: string): void {
        console.log('Attempting to send JOIN with name:', name);
        if (this.socket && this.socket.readyState === WebSocket.OPEN) {
            const payload = JSON.stringify({ type: 'JOIN', name: name });
            console.log('Sending Payload:', payload);
            this.socket.send(payload);
        } else {
            console.error('Cannot send JOIN. Socket is not OPEN. State:', this.socket?.readyState);
        }
    }

    sendMessage(content: string): void {
        if (this.socket && this.socket.readyState === WebSocket.OPEN) {
            // Backend expects plain text or JSON?
            // "Messages are plain text only. Messages are forwarded directly in memory."
            // But in handler: "Assume input matches output format expectation. ... Let's forward."
            // If we send raw string, partner gets raw string.
            // But partner frontend expects JSON in `onmessage`.
            // So we MUST send JSON from here for the partner to parse it correctly.
            const payload = JSON.stringify({ type: 'MESSAGE', content: content });
            this.socket.send(payload);
        }
    }

    disconnect(): void {
        if (this.socket) {
            this.socket.close();
            this.socket = null;
        }
        this.stopHeartbeat();
    }
}
