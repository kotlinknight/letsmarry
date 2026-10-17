package com.makemymarriage.identity.api;

record ResetPasswordRequest(String token, String newPassword) {}
