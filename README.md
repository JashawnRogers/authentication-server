# Authentication Server

This project is a Spring Boot authentication server built to practice Spring Security authentication and authorization. It uses Spring Security, Spring Data JPA, PostgreSQL, BCrypt password hashing, and JSON Web Tokens (JWTs).

The main idea is:

1. A user registers or authenticates with an email and password.
2. The server verifies or creates that user.
3. The server returns a JWT.
4. The client sends that JWT on future requests.
5. Spring Security validates the JWT before allowing access to protected endpoints.

## Main Pieces

### Auth Layer

- `AuthenticationController`
  - Exposes the public authentication endpoints.
  - Handles:
    - `POST /api/v1/auth/register`
    - `POST /api/v1/auth/authenticate`

- `AuthenticationService`
  - Contains the registration and login logic.
  - Saves new users.
  - Authenticates existing users.
  - Generates JWTs after successful registration or login.

- Request/response records:
  - `RegisterRequest`
  - `AuthenticationRequest`
  - `AuthenticationResponse`

### User Layer

- `User`
  - JPA entity mapped to the `_user` table.
  - Implements Spring Security's `UserDetails`.
  - Uses the user's email as the Spring Security username.
  - Exposes the user's role as a granted authority.

- `Role`
  - Defines available roles:
    - `FREE`
    - `PAID`

- `UserRepository`
  - Extends `JpaRepository`.
  - Adds `findByEmail(String email)` so users can be loaded during login and JWT validation.

### Security Layer

- `SecurityConfig`
  - Defines the Spring Security filter chain.
  - Disables CSRF.
  - Allows unauthenticated access to `/api/v1/auth/**`.
  - Requires authentication for every other request.
  - Makes the application stateless.
  - Adds the JWT filter before Spring Security's username/password filter.

- `ApplicationConfig`
  - Defines security-related beans:
    - `UserDetailsService`
    - `AuthenticationProvider`
    - `AuthenticationManager`
    - `PasswordEncoder`

- `JwtAuthenticationFilter`
  - Runs once per request.
  - Looks for a JWT in the `Authorization` header.
  - Validates the JWT.
  - If valid, stores the authenticated user in Spring Security's `SecurityContextHolder`.

- `JwtService`
  - Generates JWTs.
  - Extracts claims from JWTs.
  - Validates token ownership and expiration.
  - Uses the configured secret key to sign and verify tokens.

## Request Flow: Register

Endpoint:

```http
POST /api/v1/auth/register
```

Example request body:

```json
{
  "firstName": "Jashawn",
  "lastName": "Rogers",
  "email": "jashawn@example.com",
  "password": "password123"
}
```

### 1. Request Enters Spring Security

Every request enters the Spring Security filter chain first.

In `SecurityConfig`, this rule allows the request through without requiring a JWT:

```java
.requestMatchers("/api/v1/auth/**").permitAll()
```

Because `/api/v1/auth/register` matches that pattern, the request is allowed to reach the controller.

### 2. Controller Receives the Request

`AuthenticationController.register()` receives the JSON body as a `RegisterRequest`.

It delegates the work to:

```java
authenticationService.register(request)
```

The controller itself does not create users or tokens. It only accepts the request and returns the service response.

### 3. Service Builds a User

`AuthenticationService.register()` creates a new `User` from the request:

- `firstName` comes from the request.
- `lastName` comes from the request.
- `email` comes from the request.
- `password` is encoded with BCrypt.
- `role` is set to `Role.FREE`.

The important part is password encoding:

```java
passwordEncoder.encode(request.password())
```

The raw password is not saved directly. The database stores the BCrypt hash.

### 4. User Is Saved

The service saves the user:

```java
userRepository.save(user)
```

Because `UserRepository` extends `JpaRepository`, Spring Data JPA handles the insert into the `_user` table.

### 5. JWT Is Generated

After saving the user, the service generates a JWT:

```java
jwtService.generateToken(user)
```

Since `User` implements `UserDetails`, it can be passed directly into the JWT service.

Inside `JwtService`, the token includes:

- Subject: the user's username, which is the user's email.
- Issued-at timestamp.
- Expiration timestamp.
- Signature created with the configured secret key.

### 6. Response Is Returned

The server responds with:

```json
{
  "token": "jwt-token-value"
}
```

The client is expected to store this token and send it on future protected requests.

## Request Flow: Authenticate

Endpoint:

```http
POST /api/v1/auth/authenticate
```

Example request body:

```json
{
  "email": "jashawn@example.com",
  "password": "password123"
}
```

### 1. Request Enters Spring Security

Like registration, this endpoint is under `/api/v1/auth/**`, so it is public.

The request does not need a JWT yet because the goal of this endpoint is to get one.

### 2. Controller Receives the Request

`AuthenticationController.authenticate()` receives the JSON body as an `AuthenticationRequest`.

It delegates to:

```java
authenticationService.authenticate(request)
```

### 3. AuthenticationManager Verifies Credentials

The service creates a `UsernamePasswordAuthenticationToken`:

```java
new UsernamePasswordAuthenticationToken(request.email(), request.password())
```

Then it passes that token to:

```java
authenticationManager.authenticate(...)
```

This is where Spring Security checks whether the email and password are valid.

### 4. AuthenticationProvider Loads the User

The `AuthenticationManager` uses the configured `DaoAuthenticationProvider`.

That provider uses the `UserDetailsService` from `ApplicationConfig`:

```java
username -> userRepository.findByEmail(username)
```

In this project, the "username" is the user's email address.

If no user exists with that email, Spring throws `UsernameNotFoundException`.

### 5. PasswordEncoder Checks the Password

The `DaoAuthenticationProvider` compares:

- The raw password from the request.
- The encoded BCrypt password stored in the database.

It uses the configured `BCryptPasswordEncoder`.

If the password does not match, authentication fails and the request does not receive a token.

### 6. User Is Loaded Again for Token Creation

After successful authentication, the service loads the user from the repository:

```java
userRepository.findByEmail(request.email())
```

Then it generates a JWT for that user.

### 7. Response Is Returned

The response has the same shape as registration:

```json
{
  "token": "jwt-token-value"
}
```

At this point, the client can use the token to access protected endpoints.

## Request Flow: Protected Endpoint

Any endpoint outside `/api/v1/auth/**` requires authentication.

The client must send the JWT in the `Authorization` header:

```http
Authorization: Bearer jwt-token-value
```

### 1. Request Enters the Security Filter Chain

All requests pass through the `SecurityFilterChain`.

This project adds `JwtAuthenticationFilter` before Spring Security's `UsernamePasswordAuthenticationFilter`:

```java
.addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
```

That means the JWT filter gets a chance to authenticate the request before Spring checks authorization rules.

### 2. JwtAuthenticationFilter Reads the Authorization Header

The filter checks:

```java
request.getHeader("Authorization")
```

If the header is missing or does not start with `Bearer `, the filter does not authenticate the request. It passes the request to the next filter.

For protected endpoints, this eventually means Spring Security rejects the request because there is no authenticated user.

### 3. JWT Is Extracted

If the header starts with `Bearer `, the filter removes that prefix:

```java
jwt = authHeader.substring(7)
```

Only the JWT string remains.

### 4. Email Is Extracted from the Token

The filter calls:

```java
jwtService.extractUsername(jwt)
```

In this project, the JWT subject is the user's email. So extracting the username means extracting the email from the token claims.

### 5. SecurityContextHolder Is Checked

The filter only continues authentication if:

- The email was extracted successfully.
- There is not already an authenticated user in `SecurityContextHolder`.

This prevents the filter from replacing an authentication that already exists for the request.

### 6. UserDetailsService Loads the User

The filter calls:

```java
userDetailsService.loadUserByUsername(userEmail)
```

That goes back to the `UserRepository` and finds the user by email.

The returned `User` object contains:

- Email.
- Hashed password.
- Role.
- Granted authorities.

### 7. JwtService Validates the Token

The filter calls:

```java
jwtService.validateToken(jwt, userDetails)
```

Validation checks two things:

1. The email inside the token matches `userDetails.getUsername()`.
2. The token is not expired.

The JWT parser also verifies the signature using the configured secret key. This proves the token was signed by this server's secret and was not modified.

### 8. Authentication Object Is Created

If the token is valid, the filter creates a Spring Security authentication object:

```java
new UsernamePasswordAuthenticationToken(
    userDetails,
    null,
    userDetails.getAuthorities()
)
```

The password/credentials value is `null` because the user is already authenticated by the JWT.

The authorities come from the user's role. For example, a `FREE` user returns:

```java
new SimpleGrantedAuthority("FREE")
```

### 9. SecurityContextHolder Is Updated

The filter stores the authentication object here:

```java
SecurityContextHolder.getContext().setAuthentication(authenticationToken)
```

This is the key moment where Spring Security now considers the request authenticated.

From this point forward, the rest of the request can access the authenticated principal and authorities.

### 10. Authorization Rules Are Applied

After the JWT filter finishes, the request continues through the rest of Spring Security.

In this project, the current rule is simple:

```java
.anyRequest().authenticated()
```

That means any valid logged-in user can access any endpoint outside `/api/v1/auth/**`.

The app currently has roles, but there are no role-specific route rules yet. For example, there is no rule like:

```java
.requestMatchers("/api/v1/admin/**").hasAuthority("PAID")
```

So authorization is currently based on whether the request is authenticated, not whether the user has a specific role.

### 11. Request Reaches the Controller

If authentication succeeds, Spring allows the request to continue to the target controller.

If authentication fails, the controller is never called.

## Stateless Sessions

This server uses:

```java
SessionCreationPolicy.STATELESS
```

That means the server does not create or rely on an HTTP session to remember who the user is.

Every protected request must bring its own proof of authentication by sending the JWT.

This is why the JWT filter runs on every request.

## Authentication vs Authorization in This Project

### Authentication

Authentication answers:

> Who are you?

In this project, authentication happens when:

- A user logs in with email and password.
- A request sends a valid JWT.

Spring Security represents the authenticated user by storing an `Authentication` object in `SecurityContextHolder`.

### Authorization

Authorization answers:

> What are you allowed to access?

In this project, authorization is currently basic:

- `/api/v1/auth/**` is public.
- Everything else requires any authenticated user.

Roles exist through `Role.FREE` and `Role.PAID`, and each user exposes their role as a granted authority. However, the current security configuration does not yet restrict endpoints by role.

## Example Usage

### Register

```bash
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "firstName": "Jashawn",
    "lastName": "Rogers",
    "email": "jashawn@example.com",
    "password": "password123"
  }'
```

### Authenticate

```bash
curl -X POST http://localhost:8080/api/v1/auth/authenticate \
  -H "Content-Type: application/json" \
  -d '{
    "email": "jashawn@example.com",
    "password": "password123"
  }'
```

### Call a Protected Endpoint

```bash
curl http://localhost:8080/some/protected/endpoint \
  -H "Authorization: Bearer jwt-token-value"
```

## Configuration

The application expects PostgreSQL and these environment variables:

```properties
DB_USERNAME=your_database_username
DB_PASSWORD=your_database_password
SECRET_KEY=your_base64_encoded_jwt_secret
```

The database URL is configured in `application.properties`:

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/auth_server_practice
```

Hibernate is configured with:

```properties
spring.jpa.hibernate.ddl-auto=create-drop
```

That means the schema is created when the app starts and dropped when the app stops. This is useful for practice, but it is not something to use for persistent production data.

## Implementation Notes

- The `_user` table name avoids a conflict with PostgreSQL's built-in `user` keyword/table behavior.
- `User.getUsername()` returns the email, so Spring Security treats email as the username.
- Passwords are stored with BCrypt, not plain text.
- JWT authentication is stateless, so there is no server-side login session.
- The JWT contains the email as the subject.
- The JWT is sent back to the client as `{ "token": "..." }`.
- The client must send the token back as `Authorization: Bearer ...`.
- Roles are exposed as Spring Security authorities, but the current app only checks for authentication, not role-specific authorization.
- As written, the JWT expiration calculation is `System.currentTimeMillis() + 1000 + 60 + 24`, which is only a little over one second. If the goal is a longer token lifetime, this value should be adjusted.
- `JwtService` currently declares `SECRET_KEY` as `static` while using `@Value`. Spring normally injects instance fields, so this may need to become a non-static field if token signing fails at runtime.

## Mental Model

Think of the server in two phases:

### Phase 1: Get a Token

The user proves who they are with a password.

```text
request -> auth endpoint -> AuthenticationService -> database/password check -> JwtService -> response with token
```

### Phase 2: Use the Token

The user proves who they are by sending the JWT.

```text
request -> JwtAuthenticationFilter -> JwtService validates token -> SecurityContextHolder stores user -> controller handles request
```

The first phase uses credentials. The second phase uses the token created from successful credentials.
