import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable } from 'rxjs';

import { AuthUser } from '../models/auth.model';

/**
 * Single source of truth for the browser session. Keeping token access here
 * (instead of in AuthService) avoids a DI cycle between HttpClient and the
 * auth interceptor.
 *
 * NOTE: localStorage is used for demo simplicity. A production application
 * should discuss the XSS trade-off and typically prefer an httpOnly cookie or
 * a short-lived in-memory token with refresh.
 */
@Injectable({ providedIn: 'root' })
export class TokenStorageService {
  private readonly tokenKey = 'com.accessToken';
  private readonly userKey = 'com.currentUser';
  private readonly userSubject = new BehaviorSubject<AuthUser | null>(this.readUser());

  readonly user$: Observable<AuthUser | null> = this.userSubject.asObservable();

  get currentUser(): AuthUser | null {
    return this.userSubject.value;
  }

  get token(): string | null {
    const token = localStorage.getItem(this.tokenKey);
    if (!token) {
      return null;
    }
    if (this.isExpired(token)) {
      this.clear();
      return null;
    }
    return token;
  }

  hasValidSession(): boolean {
    return Boolean(this.token && this.currentUser);
  }

  setSession(token: string, user: AuthUser): void {
    localStorage.setItem(this.tokenKey, token);
    localStorage.setItem(this.userKey, JSON.stringify(user));
    this.userSubject.next(user);
  }

  clear(): void {
    localStorage.removeItem(this.tokenKey);
    localStorage.removeItem(this.userKey);
    this.userSubject.next(null);
  }

  private isExpired(token: string): boolean {
    try {
      const payload = JSON.parse(this.decodePayload(token)) as { exp?: number };
      return typeof payload.exp !== 'number' || payload.exp * 1000 <= Date.now();
    } catch {
      return true;
    }
  }

  private decodePayload(token: string): string {
    const base64 = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
    const padded = base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), '=');
    return atob(padded);
  }

  private readUser(): AuthUser | null {
    const raw = localStorage.getItem(this.userKey);
    if (!raw) {
      return null;
    }
    try {
      return JSON.parse(raw) as AuthUser;
    } catch {
      return null;
    }
  }
}
