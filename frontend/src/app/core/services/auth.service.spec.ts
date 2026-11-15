import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { AuthUser, LoginResponse } from '../models/auth.model';
import { AuthService } from './auth.service';
import { TokenStorageService } from './token-storage.service';

function tokenValidFor(seconds: number): string {
  const payload = btoa(
    JSON.stringify({ sub: 'admin', exp: Math.floor(Date.now() / 1000) + seconds })
  )
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');
  return `header.${payload}.signature`;
}

describe('AuthService', () => {
  let service: AuthService;
  let httpMock: HttpTestingController;
  let tokenStorage: TokenStorageService;

  const user: AuthUser = { id: 1, username: 'admin', role: 'ADMIN' };

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(AuthService);
    httpMock = TestBed.inject(HttpTestingController);
    tokenStorage = TestBed.inject(TokenStorageService);
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.clear();
  });

  it('posts credentials and stores the session on success', () => {
    const token = tokenValidFor(3600);
    const response: LoginResponse = {
      accessToken: token,
      tokenType: 'Bearer',
      expiresInSeconds: 3600,
      user
    };

    service.login({ username: 'admin', password: 'Admin@123' }).subscribe();

    const request = httpMock.expectOne('/api/auth/login');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ username: 'admin', password: 'Admin@123' });

    request.flush(response);

    expect(service.currentUser).toEqual(user);
    expect(service.isAuthenticated()).toBeTrue();
    expect(service.isAdmin()).toBeTrue();
    expect(tokenStorage.token).toBe(token);
  });

  it('leaves the session empty when credentials are rejected', () => {
    let receivedStatus: number | undefined;

    service.login({ username: 'admin', password: 'wrong' }).subscribe({
      error: (error) => (receivedStatus = error.status)
    });

    httpMock
      .expectOne('/api/auth/login')
      .flush({ message: 'Invalid username or password' }, { status: 401, statusText: 'Unauthorized' });

    expect(receivedStatus).toBe(401);
    expect(service.currentUser).toBeNull();
    expect(service.isAuthenticated()).toBeFalse();
  });

  it('clears the stored session on logout', () => {
    tokenStorage.setSession(tokenValidFor(3600), user);
    expect(service.isAuthenticated()).toBeTrue();

    service.logout();

    expect(service.currentUser).toBeNull();
    expect(service.isAuthenticated()).toBeFalse();
    expect(tokenStorage.token).toBeNull();
  });
});
