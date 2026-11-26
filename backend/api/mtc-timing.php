<?php
declare(strict_types=1);

header('Content-Type: application/json; charset=utf-8');

if ($_SERVER['REQUEST_METHOD'] !== 'GET') {
    http_response_code(405);
    echo json_encode([
        "status" => "error",
        "message" => "Only GET requests are allowed"
    ], JSON_UNESCAPED_UNICODE);
    exit;
}

$route = isset($_GET['route']) ? trim((string)$_GET['route']) : '';
$from = isset($_GET['from']) ? trim((string)$_GET['from']) : '';
$to = isset($_GET['to']) ? trim((string)$_GET['to']) : '';

if ($route === '') {
    http_response_code(400);
    echo json_encode([
        "status" => "error",
        "message" => "Parameter 'route' is required"
    ], JSON_UNESCAPED_UNICODE);
    exit;
}

// Function to perform cURL request to MTC endpoints
function fetchMtcEndpoint(string $endpoint, array $params): string {
    $url = "https://mtcbus.tn.gov.in/Home/" . $endpoint . "?" . http_build_query($params);
    $ch = curl_init($url);
    curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);
    curl_setopt($ch, CURLOPT_FOLLOWLOCATION, true);
    curl_setopt($ch, CURLOPT_TIMEOUT, 8);
    curl_setopt($ch, CURLOPT_SSL_VERIFYPEER, false);
    curl_setopt($ch, CURLOPT_SSL_VERIFYHOST, false);
    curl_setopt($ch, CURLOPT_HTTPHEADER, [
        'User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) TourCompanion/1.0',
        'X-Requested-With: XMLHttpRequest',
        'Referer: https://mtcbus.tn.gov.in/'
    ]);
    $res = curl_exec($ch);
    curl_close($ch);
    return is_string($res) ? $res : '';
}

function parseMtcOptions(string $html): array {
    $results = [];
    if (preg_match_all('/<option[^>]*value=["\']([^"\']*)["\'][^>]*>(.*?)<\/option>/is', $html, $m, PREG_SET_ORDER)) {
        foreach ($m as $match) {
            $val = trim($match[1]);
            $text = trim(strip_tags($match[2]));
            $cand = $val !== '' ? $val : $text;
            if ($cand !== '' && $cand !== '0' && stripos($cand, '--') === false) {
                $results[] = $cand;
            }
        }
    }
    return array_values(array_unique($results));
}

function parseMtcTimings(string $html): array {
    $times = [];
    // Extract times before caret delimiter
    $content = explode('^', $html)[0] ?? $html;
    if (preg_match_all('/\b([01]?[0-9]|2[0-3]):([0-5][0-9])\b/', $content, $m)) {
        foreach ($m[0] as $t) {
            $times[] = $t;
        }
    }
    return array_values(array_unique($times));
}

function formatOriginDisplay(string $raw): string {
    $trimmed = trim($raw);
    $cleaned = trim(preg_replace('/(?i)\bB\.?S\.?$/', '', $trimmed));
    $words = preg_split('/\s+/', $cleaned);
    $formatted = array_map(function($w) {
        if (strcasecmp($w, 'M.G.R.CENTRAL') === 0) return 'M.G.R. Central';
        return ucfirst(strtolower($w));
    }, $words);
    return implode(' ', $formatted);
}

try {
    $csrfToken = "f377f0d9102f3ade66916c76e6769191";

    // 1. Get Origins from MTC
    $originHtml = fetchMtcEndpoint('getoriginbyroute', [
        'csrf_test_name' => $csrfToken,
        'selroute' => $route,
        'selfrom' => '',
        'selto' => ''
    ]);
    $origins = parseMtcOptions($originHtml);
    if (empty($origins)) {
        echo json_encode([
            "status" => "error",
            "message" => "No origins found for route $route"
        ], JSON_UNESCAPED_UNICODE);
        exit;
    }

    $selectedOrigin = '';
    if ($from === '') {
        if (count($origins) === 1) {
            $selectedOrigin = $origins[0];
        } else {
            // Multiple origins: never auto-select the first one
            $examples = array_slice($origins, 0, 2);
            $formattedExamples = array_map('formatOriginDisplay', $examples);
            $exampleStr = implode(' or ', $formattedExamples);
            echo json_encode([
                "status" => "multiple_origins",
                "route" => $route,
                "origins" => $origins,
                "message" => "Route $route has multiple starting points. Please tell me your starting point, such as $exampleStr."
            ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
            exit;
        }
    } else {
        foreach ($origins as $orig) {
            if (stripos($orig, $from) !== false || stripos($from, $orig) !== false) {
                $selectedOrigin = $orig;
                break;
            }
        }
        if ($selectedOrigin === '') {
            echo json_encode([
                "status" => "error",
                "message" => "No matching starting point '$from' found for route $route"
            ], JSON_UNESCAPED_UNICODE);
            exit;
        }
    }

    // 2. Get Destinations for route and selected origin
    $destHtml = fetchMtcEndpoint('getdestinationrouteorigin', [
        'csrf_test_name' => $csrfToken,
        'selroute' => $route,
        'selfrom' => $selectedOrigin,
        'selto' => ''
    ]);
    $destinations = parseMtcOptions($destHtml);
    if (empty($destinations)) {
        echo json_encode([
            "status" => "error",
            "message" => "No destinations found for route $route from $selectedOrigin"
        ], JSON_UNESCAPED_UNICODE);
        exit;
    }

    $selectedDest = '';
    if ($to === '') {
        $selectedDest = $destinations[0];
    } else {
        foreach ($destinations as $dest) {
            if (stripos($dest, $to) !== false || stripos($to, $dest) !== false) {
                $selectedDest = $dest;
                break;
            }
        }
        if ($selectedDest === '') {
            echo json_encode([
                "status" => "error",
                "message" => "No matching destination '$to' found for route $route from $selectedOrigin"
            ], JSON_UNESCAPED_UNICODE);
            exit;
        }
    }

    // 3. Get Timings
    $timingHtml = fetchMtcEndpoint('ajaxbustimingsearch', [
        'csrf_test_name' => $csrfToken,
        'selroute' => $route,
        'selfrom' => $selectedOrigin,
        'selto' => $selectedDest
    ]);

    $timings = parseMtcTimings($timingHtml);

    if (empty($timings)) {
        echo json_encode([
            "status" => "error",
            "message" => "No scheduled departure times found for route $route from $selectedOrigin to $selectedDest"
        ], JSON_UNESCAPED_UNICODE);
        exit;
    }

    echo json_encode([
        "status" => "success",
        "route" => $route,
        "origin" => $selectedOrigin,
        "destination" => $selectedDest,
        "timings" => $timings,
        "source" => "official_mtc_scheduled"
    ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);

} catch (Throwable $e) {
    http_response_code(500);
    echo json_encode([
        "status" => "error",
        "message" => "Failed to retrieve MTC route timing: " . $e->getMessage()
    ], JSON_UNESCAPED_UNICODE);
}
