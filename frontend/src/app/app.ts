import { Component } from '@angular/core';
import { ChatComponent } from './chat/chat.component';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [ChatComponent],
  template: '<app-chat></app-chat>',
  styles: [] // Remove default styling
})
export class AppComponent {
  title = 'frontend';
}
