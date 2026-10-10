import { DOCUMENT } from '@angular/common';
import { TestBed } from '@angular/core/testing';
import { ThemeService } from './theme.service';

describe('ThemeService', () => {
  beforeEach(() => {
    window.localStorage.clear();
    document.documentElement.dataset['theme'] = 'light';
    TestBed.configureTestingModule({});
  });

  afterEach(() => {
    window.localStorage.clear();
    document.documentElement.dataset['theme'] = 'light';
    document.documentElement.style.colorScheme = 'light';
  });

  it('restores a stored theme and applies it to the document', () => {
    window.localStorage.setItem('mmm-theme', 'dark');

    const service = TestBed.inject(ThemeService);
    const testDocument = TestBed.inject(DOCUMENT);

    expect(service.theme()).toBe('dark');
    expect(testDocument.documentElement.dataset['theme']).toBe('dark');
    expect(testDocument.documentElement.style.colorScheme).toBe('dark');
  });

  it('toggles and persists the active theme', () => {
    const service = TestBed.inject(ThemeService);
    const initialTheme = service.theme();

    service.toggle();

    const expectedTheme = initialTheme === 'light' ? 'dark' : 'light';
    expect(service.theme()).toBe(expectedTheme);
    expect(window.localStorage.getItem('mmm-theme')).toBe(expectedTheme);
  });
});
