import { useEffect, useRef } from 'react';
import { useLocation } from 'react-router-dom';

/**
 * Pushes a page-view event into the GTM dataLayer on every react-router navigation.
 *
 * WHY THIS IS NOT OPTIONAL ON THIS APP
 * GTM's built-in "Page View" trigger fires once, when gtm.js loads. Influora is a single-page
 * app (react-router-dom v7, TECH-STACK.md): after the first load, moving from /pricing to
 * /brand/dashboard to /brand/campaigns never reloads the document, so that trigger never fires
 * again. Without this component the container would report one pageview per SESSION and every
 * route past the entry page would be invisible — an install that looks healthy in Tag Assistant
 * (the tag DID fire) while the reports are wrong.
 *
 * GTM's "History Change" trigger would also catch react-router's pushState calls, but it depends
 * on a container setting nobody can see from the codebase. An explicit named event is something
 * this repo can point at.
 *
 * HOW TO WIRE IT UP IN THE GTM UI (GTM-K7LNG26G)
 *   Trigger:  Custom Event, event name `influora_page_view`
 *   Use it INSTEAD OF "All Pages" for pageview tags, not in addition to it. This fires on the
 *   first render too, so a tag on both triggers double-counts the entry page.
 *   Variables: Data Layer Variable `page_path` / `page_search` / `page_title`.
 *
 * No-ops safely when the dataLayer does not exist — during prerender and in the Playwright
 * suite, public/site-tags.js deliberately does not run (see its navigator.webdriver guard), so
 * `window.dataLayer` is undefined and the optional call below does nothing rather than throwing.
 * The same is true for visitors with an ad blocker.
 */

declare global {
  interface Window {
    dataLayer?: Record<string, unknown>[];
  }
}

export function RouteAnalytics(): null {
  const location = useLocation();
  // React StrictMode double-invokes effects in dev, and a parent re-render with an unchanged
  // location would otherwise re-push. Both would inflate pageviews in real reporting.
  const lastPath = useRef<string | null>(null);

  useEffect(() => {
    const path = location.pathname + location.search;
    if (lastPath.current === path) return;
    lastPath.current = path;

    window.dataLayer?.push({
      event: 'influora_page_view',
      page_path: location.pathname,
      page_search: location.search,
      // Read after commit, so React 19's hoisted <title> from <Seo> is usually already applied.
      // "Usually": a route that resolves its title inside a lazy boundary can still be one beat
      // behind here. Key reports off page_path, which is always exact, and treat page_title as a
      // convenience label.
      page_title: document.title,
    });
  }, [location.pathname, location.search]);

  return null;
}

export default RouteAnalytics;
