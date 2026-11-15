export const environment = {
  production: false,
  // Relative URL: the dev-server proxy and the nginx container both forward
  // /api to the Spring Cloud Gateway. The browser never calls a service port.
  apiBaseUrl: '/api',
  defaultPageSize: 10
};
