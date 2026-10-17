import {
  AbstractControl,
  FormControl,
  ValidationErrors,
  ValidatorFn,
  Validators,
} from '@angular/forms';

export const normalizedEmail: ValidatorFn = (control: AbstractControl): ValidationErrors | null => {
  const value = String(control.value ?? '')
    .trim()
    .toLowerCase();
  if (!value) return { required: true };
  const candidate = new FormControl(value);
  return Validators.email(candidate) ?? Validators.maxLength(320)(candidate);
};

export const passwordPolicy: ValidatorFn = (control: AbstractControl): ValidationErrors | null => {
  const value = control.value;
  if (typeof value !== 'string') return { passwordPolicy: true };
  const normalized = value.normalize('NFC');
  const length = Array.from(normalized).length;
  return length >= 15 && length <= 128 && !/[\p{Cc}]/u.test(normalized)
    ? null
    : { passwordPolicy: true };
};
