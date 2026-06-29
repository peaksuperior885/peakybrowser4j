package com.peak885.peakybrowser4j.browser.css;

public final class UserAgentStyles {

    private UserAgentStyles() {}

    public static final String CSS = """
        body {
            background-color: #ffffff;
            color: #202020;
            font-family: Arial, sans-serif;
            font-size: 16px;
            line-height: 1.2;
            margin: 8px;
            padding: 0;
        }

        html {
            display: block;
            margin: 0;
            padding: 0;
        }

        head, meta, title, link, style, script, template, noscript {
            display: none;
        }

        div {
            display: block;
        }

        article, aside, footer, header, main, nav, section {
            display: block;
        }

        h1 {
            display: block;
            font-size: 2em;
            margin: 0.67em 0;
            font-weight: bold;
        }

        h2 {
            display: block;
            font-size: 1.5em;
            margin: 0.83em 0;
            font-weight: bold;
        }

        h3 {
            display: block;
            font-size: 1.17em;
            margin: 1em 0;
            font-weight: bold;
        }

        h4 {
            display: block;
            font-size: 1em;
            margin: 1.33em 0;
            font-weight: bold;
        }

        h5 {
            display: block;
            font-size: 0.83em;
            margin: 1.67em 0;
            font-weight: bold;
        }

        h6 {
            display: block;
            font-size: 0.67em;
            margin: 2.33em 0;
            font-weight: bold;
        }

        p {
            display: block;
            margin: 1em 0;
        }

        ul, ol, menu {
            display: block;
            margin: 1em 0;
            padding-left: 40px;
        }

        ul {
            list-style-type: disc;
        }

        ol {
            list-style-type: decimal;
        }

        li {
            display: list-item;
        }

        blockquote {
            display: block;
            margin: 1em 40px;
        }

        hr {
            display: block;
            margin: 0.5em 0;
            border: none;
            border-top: 1px solid #cccccc;
            height: 0;
        }

        pre {
            display: block;
            font-family: monospace;
            white-space: pre;
            margin: 1em 0;
            padding: 1em;
            background-color: #f5f5f5;
            border: 1px solid #ddd;
        }

        table {
            display: table;
            border-collapse: collapse;
            border-spacing: 0;
            border: 1px solid #999;
        }

        tr {
            display: table-row;
        }

        td, th {
            display: table-cell;
            border: 1px solid #999;
            padding: 4px 6px;
        }

        th {
            font-weight: bold;
            background-color: #f0f0f0;
            text-align: center;
        }

        a {
            color: #0066cc;
            text-decoration: underline;
        }

        strong, b {
            font-weight: bold;
        }

        em, i {
            font-style: italic;
        }

        code, kbd, samp, tt {
            font-family: monospace;
            font-size: 0.9em;
            background-color: #f5f5f5;
            border: 1px solid #ddd;
            padding: 2px 4px;
        }

        small {
            font-size: 0.8em;
        }

        mark {
            background-color: yellow;
            color: black;
        }

        img {
            display: inline-block;
            max-width: 100%;
            height: auto;
        }

        form {
            display: block;
            margin: 0;
        }

        fieldset {
            display: block;
            margin: 1em 0;
            padding: 1em;
            border: 2px groove #999;
        }

        legend {
            padding: 0 4px;
            font-weight: bold;
        }

        label {
            display: inline;
            cursor: pointer;
        }

        input, textarea, select, button {
            display: inline-block;
            margin: 4px;
            padding: 6px 8px;
            font-family: inherit;
            font-size: 16px;
            border: 1px solid #999;
            background-color: #ffffff;
            color: #202020;
        }

        textarea {
            min-width: 20em;
            min-height: 8em;
            resize: both;
            white-space: pre-wrap;
        }

        button {
            padding: 6px 12px;
            border: 2px outset #999;
            background-color: #e8e8e8;
            font-weight: bold;
            cursor: pointer;
        }

        :focus {
            outline: 2px solid #0066cc;
            outline-offset: 1px;
        }
        """;
}