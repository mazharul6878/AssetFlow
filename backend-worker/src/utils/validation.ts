// Input validation and sanitization utilities

export function isValidJSON(str: string): boolean {
  try {
    JSON.parse(str);
    return true;
  } catch {
    return false;
  }
}

export function sanitizeString(val: string): string {
  return val.trim();
}

export function validateClientId(clientId: string): boolean {
  return /^[a-zA-Z0-9_\-]{2,64}$/.test(clientId);
}

export function validateFiscalYear(fy: string): boolean {
  return /^[a-zA-Z0-9_\-]{2,32}$/.test(fy);
}

export function validateAssetKey(assetKey: string): boolean {
  return /^[a-zA-Z0-9_\-\.\:\/]+$/.test(assetKey);
}
