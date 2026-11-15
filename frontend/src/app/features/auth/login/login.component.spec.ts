import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { of, throwError } from 'rxjs';

import { LoginResponse } from '../../../core/models/auth.model';
import { AuthService } from '../../../core/services/auth.service';
import { LoginComponent } from './login.component';

describe('LoginComponent', () => {
  let fixture: ComponentFixture<LoginComponent>;
  let component: LoginComponent;
  let router: Router;
  let authMock: jasmine.SpyObj<AuthService>;

  const adminResponse: LoginResponse = {
    accessToken: 'signed.jwt.token',
    tokenType: 'Bearer',
    expiresInSeconds: 3600,
    user: { id: 1, username: 'admin', role: 'ADMIN' }
  };

  beforeEach(async () => {
    authMock = jasmine.createSpyObj<AuthService>('AuthService', ['login']);

    await TestBed.configureTestingModule({
      imports: [LoginComponent],
      providers: [provideRouter([]), { provide: AuthService, useValue: authMock }]
    }).compileComponents();

    fixture = TestBed.createComponent(LoginComponent);
    component = fixture.componentInstance;
    router = TestBed.inject(Router);
    spyOn(router, 'navigateByUrl').and.resolveTo(true);
    fixture.detectChanges();
  });

  it('does not call the API when the form is empty', () => {
    component.onSubmit();

    expect(component.submitted).toBeTrue();
    expect(component.usernameControl.touched).toBeTrue();
    expect(component.passwordControl.touched).toBeTrue();
    expect(authMock.login).not.toHaveBeenCalled();
  });

  it('fills the demo credentials and validates the form', () => {
    component.useDemo('operator', 'Operator@123');

    expect(component.loginForm.getRawValue()).toEqual({
      username: 'operator',
      password: 'Operator@123'
    });
    expect(component.loginForm.valid).toBeTrue();
  });

  it('navigates administrators to the dashboard after a successful login', () => {
    authMock.login.and.returnValue(of(adminResponse));
    component.useDemo('admin', 'Admin@123');

    component.onSubmit();

    expect(authMock.login).toHaveBeenCalledWith({ username: 'admin', password: 'Admin@123' });
    expect(router.navigateByUrl).toHaveBeenCalledWith('/dashboard');
    expect(component.loading).toBeFalse();
  });

  it('shows the API message when credentials are rejected', () => {
    authMock.login.and.returnValue(
      throwError(() => ({ error: { message: 'Invalid username or password' } }))
    );
    component.useDemo('admin', 'wrong-password');

    component.onSubmit();

    expect(component.errorMessage).toBe('Invalid username or password');
    expect(component.loading).toBeFalse();
  });
});
