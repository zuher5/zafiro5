# Seed tool: multi-engine web search (Tavily AI, Sogou, DuckDuckGo HTML).
# Tavily: Fast, structured AI search (requires API key in _TAVILY_API_KEY).
# Sogou: Chinese web & WeChat search.
# DuckDuckGo: Global web search without API key.
# Failures are emitted as {"error":{"code","message"}} to stdout for agent runtime classification.
import json
import sys
import urllib.parse
from concurrent.futures import ThreadPoolExecutor

import requests
from bs4 import BeautifulSoup

# Configure your Tavily API key here (e.g. "tvly-dev-...").
# Leave empty to use DuckDuckGo / Sogou by default.
_TAVILY_API_KEY = "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"

_DDG_ENDPOINT = "https://html.duckduckgo.com/html/"
_SOGOU_ENDPOINT = "https://www.sogou.com/web"
_TAVILY_ENDPOINT = "https://api.tavily.com/search"

_UA_ANDROID = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
_UA_DESKTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
_TIMEOUT_SECONDS = 15


def main(query: str, engine: str = "auto", max_results: int = 8):
    '''Search the web. engine: "auto" (Tavily, else DuckDuckGo + Sogou), "tavily", "ddg", "sogou", "all". Returns {title, url, snippet} list.'''
    if not query or not query.strip():
        _fail("EMPTY_QUERY", "Search query cannot be empty.")
        return

    query = query.strip()
    engine = (engine or "auto").strip().lower()

    if engine == "tavily":
        if not _TAVILY_API_KEY:
            _fail("MISSING_TAVILY_KEY", "Tavily API key is not configured. Set _TAVILY_API_KEY in the tool code.")
            return
        try:
            results = _search_tavily(query, max_results, _TAVILY_API_KEY)
        except Exception as exc:
            _fail("TAVILY_ERROR", f"Tavily search failed: {exc}")
            return
        if not results:
            _fail("NO_RESULTS", f"Tavily returned no results for {query!r}.")
            return
        print(json.dumps(results, ensure_ascii=False))
        return

    if engine == "sogou":
        try:
            results = _search_sogou(query, max_results)
        except Exception as exc:
            _fail("SOGOU_ERROR", f"Sogou search failed: {exc}")
            return
        if not results:
            _fail("NO_RESULTS", f"Sogou returned no results for {query!r}.")
            return
        print(json.dumps(results, ensure_ascii=False))
        return

    if engine == "ddg":
        try:
            results = _search_ddg(query, max_results)
        except Exception as exc:
            _fail("DDG_ERROR", f"DuckDuckGo search failed: {exc}")
            return
        if not results:
            _fail("NO_RESULTS", f"DuckDuckGo returned no results for {query!r}.")
            return
        print(json.dumps(results, ensure_ascii=False))
        return

    if engine == "all":
        tasks = []
        if _TAVILY_API_KEY:
            tasks.append(("tavily", lambda: _search_tavily(query, max_results, _TAVILY_API_KEY)))
        tasks.append(("sogou", lambda: _search_sogou(query, max_results)))
        tasks.append(("ddg", lambda: _search_ddg(query, max_results)))

        with ThreadPoolExecutor(max_workers=len(tasks)) as pool:
            futures = [pool.submit(fn) for _, fn in tasks]
            fetched = []
            for fut in futures:
                try:
                    fetched.append(fut.result(timeout=20))
                except Exception as exc:
                    print(f"# 'all' engine subtask failed: {exc}", file=sys.stderr)
                    fetched.append([])
        merged = _merge(fetched, max_results)
        if not merged:
            _fail("NO_RESULTS", f"All engines returned no results for {query!r}.")
            return
        print(json.dumps(merged, ensure_ascii=False))
        return

    # engine == "auto"
    errors = []
    if _TAVILY_API_KEY:
        try:
            results = _search_tavily(query, max_results, _TAVILY_API_KEY)
            if results:
                print(json.dumps(results, ensure_ascii=False))
                return
        except Exception as exc:
            errors.append(f"Tavily: {exc}")
            print(f"# Tavily fallback: {exc}", file=sys.stderr)

    is_chinese = any("\u4e00" <= ch <= "\u9fff" for ch in query)
    fallback_engines = [("sogou", _search_sogou), ("ddg", _search_ddg)] if is_chinese else [("ddg", _search_ddg), ("sogou", _search_sogou)]

    for name, fn in fallback_engines:
        try:
            results = fn(query, max_results)
            if results:
                print(json.dumps(results, ensure_ascii=False))
                return
        except Exception as exc:
            errors.append(f"{name}: {exc}")
            print(f"# {name} fallback failed: {exc}", file=sys.stderr)

    _fail("SEARCH_FAILED", f"Search engines failed for {query!r}: " + "; ".join(errors) if errors else f"No results found for {query!r}.")


def _search_tavily(query: str, max_results: int, api_key: str):
    if not api_key:
        raise ValueError("Tavily API key is not configured")
    resp = requests.post(
        _TAVILY_ENDPOINT,
        json={
            "api_key": api_key,
            "query": query,
            "max_results": max_results,
            "search_depth": "basic",
            "include_answer": False,
        },
        headers={"Content-Type": "application/json"},
        timeout=_TIMEOUT_SECONDS,
    )
    resp.raise_for_status()
    data = resp.json()
    results = []
    for item in data.get("results", []):
        title = item.get("title", "").strip()
        url = item.get("url", "").strip()
        snippet = item.get("content", "").strip()
        if title and url:
            results.append({"title": title, "url": url, "snippet": snippet})
            if len(results) >= max_results:
                break
    return results


def _search_sogou(query: str, max_results: int):
    resp = requests.get(
        _SOGOU_ENDPOINT,
        params={"query": query},
        headers={"User-Agent": _UA_DESKTOP},
        timeout=_TIMEOUT_SECONDS,
    )
    resp.raise_for_status()
    if "anti_time" in resp.text:
        raise RuntimeError("Sogou anti-bot verification triggered")
    soup = BeautifulSoup(resp.text, "html.parser")
    results = []
    for item in soup.select("div.vrwrap, div.rb"):
        if len(results) >= max_results:
            break
        link = item.select_one("h3 a")
        if link is None:
            continue
        title = link.get_text(strip=True)
        url = _clean_sogou_url(link.get("href", ""))
        if not title or not url:
            continue
        snippet_el = (
            item.select_one(".str-text-info")
            or item.select_one(".str_info")
            or item.select_one(".space-txt")
        )
        snippet = snippet_el.get_text(strip=True) if snippet_el else ""
        results.append({"title": title, "url": url, "snippet": snippet})
    if not results:
        raise RuntimeError("Sogou returned no parseable results")
    return results


def _clean_sogou_url(href: str) -> str:
    if href.startswith("/link"):
        return "https://www.sogou.com" + href
    return href


def _search_ddg(query: str, max_results: int):
    last_error = None
    for attempt in range(2):
        try:
            resp = requests.post(
                _DDG_ENDPOINT,
                data={"q": query},
                headers={"User-Agent": _UA_ANDROID},
                timeout=_TIMEOUT_SECONDS,
            )
            resp.raise_for_status()
            soup = BeautifulSoup(resp.text, "html.parser")
            results = []
            for item in soup.select("div.result.results_links"):
                if len(results) >= max_results:
                    break
                link = item.select_one("a.result__a")
                if link is None:
                    continue
                title = link.get_text(strip=True)
                url = _clean_ddg_url(link.get("href", ""))
                if not title or not url:
                    continue
                snippet_el = item.select_one("td.result__snippet") or item.select_one(".result__snippet")
                snippet = snippet_el.get_text(strip=True) if snippet_el else ""
                results.append({"title": title, "url": url, "snippet": snippet})
            if not results:
                raise RuntimeError("DuckDuckGo returned no parseable results")
            return results
        except (requests.ConnectionError, requests.Timeout) as exc:
            last_error = exc
            print(f"# DDG attempt {attempt + 1} failed: {exc}", file=sys.stderr)
    raise last_error or RuntimeError("DuckDuckGo request failed")


def _clean_ddg_url(href: str) -> str:
    if href.startswith("//duckduckgo.com/l/"):
        href = "https:" + href
    parsed = urllib.parse.urlparse(href)
    target = urllib.parse.parse_qs(parsed.query).get("uddg", [""])[0]
    return urllib.parse.unquote(target) if target else href


def _merge(lists, max_results: int):
    seen = set()
    merged = []
    for group in lists:
        for item in group:
            url = item.get("url", "")
            if not url or url in seen:
                continue
            seen.add(url)
            merged.append(item)
            if len(merged) >= max_results:
                return merged
    return merged


def _fail(code: str, message: str):
    print(json.dumps({"error": {"code": code, "message": message}}, ensure_ascii=False))
