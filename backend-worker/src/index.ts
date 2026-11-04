// Cloudflare Worker Main Entrypoint

import { Env } from './env';
import { handleRequest } from './router/routes';
import { handleRouteError } from './middleware/errorHandler';

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    // Handle CORS Preflight
    if (request.method === 'OPTIONS') {
      return new Response(null, {
        status: 204,
        headers: {
          'Access-Control-Allow-Origin': '*',
          'Access-Control-Allow-Methods': 'GET, POST, PUT, DELETE, OPTIONS',
          'Access-Control-Allow-Headers': 'Content-Type, Authorization, Idempotency-Key, X-Idempotency-Key',
          'Access-Control-Max-Age': '86400'
        }
      });
    }

    try {
      const response = await handleRequest(request, env);

      // Inject CORS headers on outgoing response
      const headers = new Headers(response.headers);
      headers.set('Access-Control-Allow-Origin', '*');

      return new Response(response.body, {
        status: response.status,
        statusText: response.statusText,
        headers
      });
    } catch (error) {
      const errorResponse = handleRouteError(error);
      const headers = new Headers(errorResponse.headers);
      headers.set('Access-Control-Allow-Origin', '*');

      return new Response(errorResponse.body, {
        status: errorResponse.status,
        headers
      });
    }
  }
};
