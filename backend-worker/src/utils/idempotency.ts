// Idempotency token validation and header extraction

export function extractIdempotencyKey(request: Request): string | null {
  const key = request.headers.get('Idempotency-Key') || request.headers.get('X-Idempotency-Key');
  if (!key) return null;
  const trimmed = key.trim();
  return trimmed.length > 0 && trimmed.length <= 128 ? trimmed : null;
}
