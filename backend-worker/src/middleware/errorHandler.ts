// Standardized JSON Error Response Handler

import { AuthenticationError } from './firebaseAuth';
import { AuthorizationError } from './authorization';

export function handleRouteError(error: unknown): Response {
  console.error('[API Error]:', error);

  if (error instanceof AuthenticationError) {
    return new Response(
      JSON.stringify({
        error: error.message,
        code: 'AUTHENTICATION_REQUIRED',
        status: error.statusCode,
        timestamp: new Date().toISOString()
      }),
      { status: error.statusCode, headers: { 'Content-Type': 'application/json' } }
    );
  }

  if (error instanceof AuthorizationError) {
    return new Response(
      JSON.stringify({
        error: error.message,
        code: (error as any).code || 'FORBIDDEN',
        status: error.statusCode,
        timestamp: new Date().toISOString()
      }),
      { status: error.statusCode, headers: { 'Content-Type': 'application/json' } }
    );
  }

  if (typeof error === 'object' && error !== null && 'statusCode' in error) {
    const statusCode = Number((error as any).statusCode) || 500;
    const message = error instanceof Error ? error.message : String((error as any).message || 'Error');
    const code = (error as any).code || (statusCode === 403 ? 'FORBIDDEN' : statusCode === 404 ? 'NOT_FOUND' : 'API_ERROR');
    return new Response(
      JSON.stringify({
        error: message,
        code,
        status: statusCode,
        timestamp: new Date().toISOString()
      }),
      { status: statusCode, headers: { 'Content-Type': 'application/json' } }
    );
  }

  const message = error instanceof Error ? error.message : 'Internal Server Error';
  return new Response(
    JSON.stringify({
      error: message,
      code: 'INTERNAL_ERROR',
      status: 500,
      timestamp: new Date().toISOString()
    }),
    { status: 500, headers: { 'Content-Type': 'application/json' } }
  );
}
