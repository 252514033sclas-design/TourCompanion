<?php
/**
 * Server-Side AI Configuration for Tour Companion
 *
 * Contains API configuration for Gemini 3.8 Flash.
 * Kept on the PHP server only and protected from Git via .gitignore.
 */

// Reads from environment if available; otherwise uses defined local value
$envKey = getenv('GEMINI_API_KEY');
define('GEMINI_API_KEY', ($envKey !== false && trim($envKey) !== '') ? trim($envKey) : '');

define('GEMINI_MODEL', 'gemini-3.8-flash');
define('GEMINI_THINKING_LEVEL', 'medium');
define('GEMINI_TIMEOUT_SECONDS', 3.5);

