# Seed tool: multi-engine web search (auto / tavily / all / baidu / sogou / ddg).
# ponytail: baidu/sogou 返回跳转链接不展开；解析选择器随站点改版会失效，Agent 可自更新本脚本。
# auto = Tavily 优先，无 key 或失败时退回 upstream 的 baidu+sogou+ddg 合并。
import json
import urllib.parse
from concurrent.futures import ThreadPoolExecutor

import requests
from bs4 import BeautifulSoup

# Tavily API key（结构化 AI 搜索）。留空则完全退回 upstream 的多引擎路径。
_TAVILY_API_KEY = "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"

_UA_ANDROID = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
_UA_DESKTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

_UPSTREAM_ENGINES = ("baidu", "sogou", "ddg")


def main(query: str, engine: str = "auto", max_results: int = 8):
    '''Search the web. engine: "auto" (default, Tavily then Baidu+Sogou+DuckDuckGo), "all" (merge every engine), "tavily", "baidu", "sogou" or "ddg". Returns a list of {title, url, snippet}.'''
    if engine == "auto":
        if _TAVILY_API_KEY:
            try:
                results = _search_tavily(query, max_results)
                if results:
                    print(json.dumps(results, ensure_ascii=False))
                    return
            except Exception:
                pass  # Tavily 挂了不该让整次搜索失败，静默退回多引擎
        engine = "all"

    if engine == "tavily":
        print(json.dumps(_search_tavily(query, max_results), ensure_ascii=False))
        return

    engines = _UPSTREAM_ENGINES if engine == "all" else (engine,)
    with ThreadPoolExecutor(max_workers=len(engines)) as pool:
        futures = [pool.submit(_search, e, query, max_results) for e in engines]
        # ddg 在无代理网络会超时，给短 timeout 快速失败，不拖慢整体
        fetched = []
        for e, fut in zip(engines, futures):
            try:
                fetched.append(fut.result(timeout=20))
            except Exception:
                fetched.append([])
    print(json.dumps(_merge(fetched, max_results), ensure_ascii=False))


def _search_tavily(query: str, max_results: int):
    if not _TAVILY_API_KEY:
        raise RuntimeError("Tavily API key is not configured")
    resp = requests.post(
        "https://api.tavily.com/search",
        json={
            "api_key": _TAVILY_API_KEY,
            "query": query,
            "max_results": max_results,
            "search_depth": "basic",
        },
        timeout=15,
    )
    resp.raise_for_status()
    results = []
    for item in resp.json().get("results", []):
        title = item.get("title", "").strip()
        url = item.get("url", "").strip()
        if title and url:
            results.append({"title": title, "url": url, "snippet": item.get("content", "").strip()})
            if len(results) >= max_results:
                break
    return results


def _search(engine: str, query: str, max_results: int):
    if engine == "baidu":
        # baidu 桌面 UA 会触发验证码，必须移动 UA
        resp = requests.get(
            "https://www.baidu.com/s",
            params={"wd": query},
            headers={"User-Agent": _UA_ANDROID},
            timeout=15,
        )
        parser = _parse_baidu
    elif engine == "sogou":
        resp = requests.get(
            "https://www.sogou.com/web",
            params={"query": query},
            headers={"User-Agent": _UA_DESKTOP},
            timeout=15,
        )
        parser = _parse_sogou
    else:
        resp = requests.post(
            "https://html.duckduckgo.com/html/",
            data={"q": query},
            headers={"User-Agent": _UA_ANDROID},
            timeout=8,
        )
        parser = _parse_ddg
    resp.raise_for_status()
    soup = BeautifulSoup(resp.text, "html.parser")
    return parser(soup, max_results)


def _merge(lists, max_results):
    seen = set()
    merged = []
    for group in lists:
        for item in group:
            url = item.get("url", "")
            if url in seen:
                continue
            seen.add(url)
            merged.append(item)
            if len(merged) >= max_results:
                return merged
    return merged


def _parse_ddg(soup, max_results):
    results = []
    for item in soup.select("div.result.results_links")[: max_results]:
        link = item.select_one("a.result__a")
        if link is None:
            continue
        snippet_el = item.select_one("td.result__snippet") or item.select_one(".result__snippet")
        _append(results, link.get_text(strip=True), _clean_ddg_url(link.get("href", "")), snippet_el)
        if len(results) >= max_results:
            break
    return results


def _parse_baidu(soup, max_results):
    results = []
    for item in soup.select("div.c-result"):
        if len(results) >= max_results:
            break
        title_el = item.select_one("h3")
        if title_el is None:
            continue
        try:
            url = json.loads(item.get("data-log", "{}")).get("mu", "")
        except ValueError:
            url = ""
        snippet_el = None
        for sel in ("[class*=content-right]", "[class*=abstract]", "[class*=clamp]", "[class*=desc]"):
            candidate = item.select_one(sel)
            if candidate is not None:
                snippet_el = candidate
                break
        _append(results, title_el.get_text(strip=True), url, snippet_el)
    return results


def _parse_sogou(soup, max_results):
    results = []
    for item in soup.select("div.vrwrap, div.rb"):
        if len(results) >= max_results:
            break
        link = item.select_one("h3 a")
        if link is None:
            continue
        snippet_el = item.select_one(".str-text-info") or item.select_one(".str_info") or item.select_one(".space-txt")
        _append(results, link.get_text(strip=True), _clean_sogou_url(link.get("href", "")), snippet_el)
    return results


def _append(results, title, url, snippet_el):
    snippet = snippet_el.get_text(strip=True) if snippet_el else ""
    if title and url:
        results.append({"title": title, "url": url, "snippet": snippet})


def _clean_ddg_url(href: str) -> str:
    if href.startswith("//duckduckgo.com/l/") or href.startswith("https://duckduckgo.com/l/"):
        parsed = urllib.parse.urlparse(href if href.startswith("http") else "https:" + href)
        target = urllib.parse.parse_qs(parsed.query).get("uddg", [""])[0]
        return urllib.parse.unquote(target)
    return href


def _clean_sogou_url(href: str) -> str:
    if href.startswith("/link"):
        return "https://www.sogou.com" + href
    return href
