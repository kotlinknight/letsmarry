import { BreakpointObserver } from '@angular/cdk/layout';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { map } from 'rxjs';
import { ThemeService } from '../../core/theme/theme.service';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, RouterLinkActive, RouterOutlet],
  selector: 'app-shell',
  styleUrl: './app-shell.component.scss',
  templateUrl: './app-shell.component.html',
})
export class AppShellComponent {
  private readonly breakpointObserver = inject(BreakpointObserver);

  readonly themeService = inject(ThemeService);
  readonly mobileNavOpen = signal(false);
  readonly isDesktop = toSignal(
    this.breakpointObserver.observe('(min-width: 56.25rem)').pipe(map((result) => result.matches)),
    { initialValue: false },
  );
  readonly themeActionLabel = computed(() =>
    this.themeService.theme() === 'light' ? 'Switch to dark theme' : 'Switch to light theme',
  );

  constructor() {
    effect(() => {
      if (this.isDesktop()) {
        this.mobileNavOpen.set(false);
      }
    });
  }

  toggleNavigation(): void {
    this.mobileNavOpen.update((isOpen) => !isOpen);
  }

  closeNavigation(): void {
    if (!this.isDesktop()) {
      this.mobileNavOpen.set(false);
    }
  }
}
