import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { App } from './app';

@Component({
  selector: 'app-test-blank',
  template: '<p>test route</p>'
})
class BlankRouteComponent {}

describe('App', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      // The root template contains a RouterOutlet, so the test router needs a
      // matching route for its initial navigation.
      providers: [provideRouter([{ path: '**', component: BlankRouteComponent }])]
    }).compileComponents();
  });

  it('creates the root component and renders the router outlet', () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();

    expect(fixture.componentInstance).toBeTruthy();
    expect(fixture.nativeElement.querySelector('router-outlet')).toBeTruthy();
  });

  it('exposes the application title used by the shell', () => {
    const fixture = TestBed.createComponent(App);

    expect(fixture.componentInstance.title).toBe('Commodity Order Management');
  });
});
