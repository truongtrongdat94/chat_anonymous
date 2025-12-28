import { Component, ElementRef, OnDestroy, OnInit, ViewChild, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ChatService, ChatMessage } from '../chat.service';
import { Subscription } from 'rxjs';

interface LogMessage {
    isSelf: boolean;
    content: string;
    system?: boolean;
}

@Component({
    selector: 'app-chat',
    standalone: true,
    imports: [CommonModule, FormsModule],
    templateUrl: './chat.component.html',
    styleUrls: ['./chat.component.css']
})
export class ChatComponent implements OnInit, OnDestroy {
    @ViewChild('scrollContainer') private scrollContainer!: ElementRef;

    // UI State
    step: 'SETUP' | 'CHATTING' = 'SETUP';

    // Setup State
    displayName: string = '';

    // Chat State
    status: string = 'CONNECTING...';
    messages: LogMessage[] = [];
    inputMessage: string = '';
    isInRoom: boolean = false;
    partnerName: string = 'Stranger';

    private sub!: Subscription;

    constructor(private chatService: ChatService, private cdr: ChangeDetectorRef) { }

    ngOnInit(): void {
        this.sub = this.chatService.messages$.subscribe(msg => {
            this.handleMessage(msg);
        });
    }

    startMatching() {
        if (!this.displayName.trim()) return;

        this.step = 'CHATTING';
        this.status = 'CONNECTING...';
        this.messages = [];
        this.isInRoom = false;

        this.chatService.connect(() => {
            // Once connected, send JOIN
            this.chatService.sendJoin(this.displayName);
        });
    }

    startNewChat() {
        // Re-use same name, just reconnect
        this.startMatching();
    }

    handleMessage(msg: ChatMessage) {
        switch (msg.type) {
            case 'WAITING':
                this.status = 'WAITING FOR PARTNER...';
                break;
            case 'MATCHED':
                this.status = 'TALKING TO ' + (msg.partnerName || 'STRANGER');
                this.partnerName = msg.partnerName || 'Stranger';
                this.isInRoom = true;
                this.messages.push({ isSelf: false, content: `You are now connected to ${this.partnerName}.`, system: true });
                break;
            case 'PARTNER_DISCONNECTED':
                this.status = 'PARTNER DISCONNECTED';
                this.isInRoom = false;
                this.messages.push({ isSelf: false, content: `${this.partnerName} has disconnected.`, system: true });
                this.chatService.disconnect();
                break;
            case 'MESSAGE':
                if (msg.content) {
                    this.messages.push({ isSelf: false, content: msg.content });
                    this.scrollToBottom();
                }
                break;
            case 'ERROR':
                alert(msg.content || 'Unknown Error');
                this.step = 'SETUP';
                this.isInRoom = false;
                break;
        }
        this.cdr.detectChanges(); // Force UI update
    }

    sendMessage() {
        if (!this.inputMessage.trim() || !this.isInRoom) return;

        const content = this.inputMessage.trim();
        this.chatService.sendMessage(content);
        this.messages.push({ isSelf: true, content: content });
        this.inputMessage = '';
        this.scrollToBottom();
    }

    onKeydown(event: KeyboardEvent) {
        if (event.key === 'Enter') {
            this.sendMessage();
        }
    }

    scrollToBottom(): void {
        setTimeout(() => {
            if (this.scrollContainer) {
                this.scrollContainer.nativeElement.scrollTop = this.scrollContainer.nativeElement.scrollHeight;
            }
        }, 50);
    }

    ngOnDestroy(): void {
        if (this.sub) {
            this.sub.unsubscribe();
        }
        this.chatService.disconnect();
    }
}
