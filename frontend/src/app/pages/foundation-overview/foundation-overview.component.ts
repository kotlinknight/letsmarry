import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

interface FoundationStatus {
  readonly label: string;
  readonly value: string;
  readonly detail: string;
  readonly tone: 'coral' | 'lilac' | 'mint';
}

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  selector: 'app-foundation-overview',
  styleUrl: './foundation-overview.component.scss',
  templateUrl: './foundation-overview.component.html',
})
export class FoundationOverviewComponent {
  readonly statuses: readonly FoundationStatus[] = [
    {
      label: 'Application',
      value: 'Angular 22',
      detail: 'Strict, standalone and route-first',
      tone: 'coral',
    },
    {
      label: 'Appearance',
      value: 'Two themes',
      detail: 'Light and dark semantic modes',
      tone: 'lilac',
    },
    {
      label: 'Design system',
      value: 'Figma-led',
      detail: 'Shared color, type and spacing tokens',
      tone: 'mint',
    },
  ];
}
