import { ChangeDetectionStrategy, Component } from '@angular/core';

interface ColorToken {
  readonly name: string;
  readonly value: string;
}

interface TokenGroup {
  readonly name: string;
  readonly description: string;
  readonly colors: readonly ColorToken[];
}

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  selector: 'app-design-tokens',
  styleUrl: './design-tokens.component.scss',
  templateUrl: './design-tokens.component.html',
})
export class DesignTokensComponent {
  readonly colorGroups: readonly TokenGroup[] = [
    {
      name: 'Coral',
      description: 'Brand energy and primary actions',
      colors: [
        { name: '200', value: '#FFC9C3' },
        { name: '400', value: '#FF8580' },
        { name: '500', value: '#FF6B6B' },
        { name: '700', value: '#C93B55' },
        { name: '800', value: '#A62D49' },
      ],
    },
    {
      name: 'Lilac',
      description: 'Creative emphasis and focus',
      colors: [
        { name: '100', value: '#EEE6FF' },
        { name: '300', value: '#C5AFFC' },
        { name: '400', value: '#A78BFA' },
        { name: '600', value: '#7C52D6' },
        { name: '700', value: '#663EB6' },
      ],
    },
    {
      name: 'Mint',
      description: 'Progress, calm and success',
      colors: [
        { name: '100', value: '#DAFAEC' },
        { name: '200', value: '#AFF0D4' },
        { name: '300', value: '#81E3BF' },
        { name: '400', value: '#52CEAA' },
        { name: '600', value: '#137D5A' },
      ],
    },
    {
      name: 'Ink',
      description: 'Content, surfaces and structure',
      colors: [
        { name: '50', value: '#F7F6FB' },
        { name: '200', value: '#DADAE7' },
        { name: '500', value: '#76778D' },
        { name: '800', value: '#2D2E46' },
        { name: '950', value: '#131323' },
      ],
    },
  ];

  readonly spacingTokens = [4, 8, 12, 16, 24, 32, 48, 64, 80] as const;
}
