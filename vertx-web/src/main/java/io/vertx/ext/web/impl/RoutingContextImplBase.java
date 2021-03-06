/*
 * Copyright 2014 Red Hat, Inc.
 *
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  and Apache License v2.0 which accompanies this distribution.
 *
 *  The Eclipse Public License is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 *
 *  The Apache License v2.0 is available at
 *  http://www.opensource.org/licenses/apache2.0.php
 *
 *  You may elect to redistribute this code under either of these licenses.
 */

package io.vertx.ext.web.impl;

import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.impl.logging.Logger;
import io.vertx.core.impl.logging.LoggerFactory;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.impl.HttpStatusException;

import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
public abstract class RoutingContextImplBase implements RoutingContext {

  static final Logger log = LoggerFactory.getLogger(RoutingContextImplBase.class);

  private final Set<RouteImpl> routes;

  protected final String mountPoint;
  protected final HttpServerRequest request;
  protected Iterator<RouteImpl> iter;
  protected RouteImpl currentRoute;
  protected AtomicInteger currentRouteNextHandlerIndex;
  protected AtomicInteger currentRouteNextFailureHandlerIndex;
  // When Route#matches executes, if it returns != 0 this flag is configured
  // to write the correct status code at the end of routing process
  protected int matchFailure;
  // the current path matched string
  protected int matchRest = -1;
  protected boolean matchNormalized;

  protected RoutingContextImplBase(String mountPoint, HttpServerRequest request, Set<RouteImpl> routes) {
    this.mountPoint = mountPoint;
    this.request = new HttpServerRequestWrapper(request);
    this.routes = routes;
    this.iter = routes.iterator();
    this.currentRouteNextHandlerIndex = new AtomicInteger(0);
    this.currentRouteNextFailureHandlerIndex = new AtomicInteger(0);
    resetMatchFailure();
  }

  @Override
  public String mountPoint() {
    return mountPoint;
  }

  @Override
  public Route currentRoute() {
    return currentRoute;
  }

  protected int currentRouteNextHandlerIndex() {
    return currentRouteNextHandlerIndex.intValue();
  }

  protected int currentRouteNextFailureHandlerIndex() {
    return currentRouteNextFailureHandlerIndex.intValue();
  }

  protected void restart() {
    this.iter = routes.iterator();
    currentRoute = null;
    next();
  }

  protected boolean iterateNext() {
    boolean failed = failed();
    if (currentRoute != null) { // Handle multiple handlers inside route object
      try {
        if (!failed && currentRoute.hasNextContextHandler(this)) {
          currentRouteNextHandlerIndex.incrementAndGet();
          resetMatchFailure();
          currentRoute.handleContext(this);
          return true;
        } else if (failed && currentRoute.hasNextFailureHandler(this)) {
          currentRouteNextFailureHandlerIndex.incrementAndGet();
          currentRoute.handleFailure(this);
          return true;
        }
      } catch (Throwable t) {
        handleInHandlerRuntimeFailure(currentRoute, failed, t);
        return true;
      }
    }
    while (iter.hasNext()) { // Search for more handlers
      RouteImpl route = iter.next();
      currentRouteNextHandlerIndex.set(0);
      currentRouteNextFailureHandlerIndex.set(0);
      try {
        int matchResult = route.matches(this, mountPoint(), failed);
        if (matchResult == 0) {
          if (log.isTraceEnabled()) log.trace("Route matches: " + route);
          resetMatchFailure();
          try {
            currentRoute = route;
            if (log.isTraceEnabled()) log.trace("Calling the " + (failed ? "failure" : "") + " handler");
            if (failed && currentRoute.hasNextFailureHandler(this)) {
              currentRouteNextFailureHandlerIndex.incrementAndGet();
              route.handleFailure(this);
            } else if (currentRoute.hasNextContextHandler(this)) {
              currentRouteNextHandlerIndex.incrementAndGet();
              route.handleContext(this);
            } else {
              continue;
            }
          } catch (Throwable t) {
            handleInHandlerRuntimeFailure(route, failed, t);
          }
          return true;
        } else if (matchResult != 404) {
          this.matchFailure = matchResult;
        }
      } catch (Throwable e) {
        if (log.isTraceEnabled()) log.trace("IllegalArgumentException thrown during iteration", e);
        // Failure in matches algorithm (If the exception is instanceof IllegalArgumentException probably is a QueryStringDecoder error!)
        if (!this.response().ended())
          unhandledFailure((e instanceof IllegalArgumentException) ? 400 : -1, e, route.router());
        return true;
      }
    }
    return false;
  }

  private void handleInHandlerRuntimeFailure(RouteImpl route, boolean failed, Throwable t) {
    if (log.isTraceEnabled()) log.trace("Throwable thrown from handler", t);
    if (!failed) {
      if (log.isTraceEnabled()) log.trace("Failing the routing");
      fail(t);
    } else {
      // Failure in handling failure!
      if (log.isTraceEnabled()) log.trace("Failure in handling failure");
      unhandledFailure(-1, t, route.router());
    }
  }


  protected void unhandledFailure(int statusCode, Throwable failure, RouterImpl router) {
    int code = statusCode != -1 ?
      statusCode :
      (failure instanceof HttpStatusException) ?
        ((HttpStatusException) failure).getStatusCode() :
        500;
    Handler<RoutingContext> errorHandler = router.getErrorHandlerByStatusCode(code);
    if (errorHandler != null) {
      try {
        errorHandler.handle(this);
      } catch (Throwable t) {
        log.error("Error in error handler", t);
      }
    }
    if (!response().ended() && !response().closed()) {
      try {
        response().setStatusCode(code);
      } catch (IllegalArgumentException e) {
        // means that there are invalid chars in the status message
        response()
            .setStatusMessage(HttpResponseStatus.valueOf(code).reasonPhrase())
            .setStatusCode(code);
      }
      response().end(response().getStatusMessage());
    }
  }

  private void resetMatchFailure() {
    this.matchFailure = 404;
  }
}
