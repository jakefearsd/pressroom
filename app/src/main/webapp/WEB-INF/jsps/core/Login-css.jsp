<%--
  Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  The ASF licenses this file to You
  under the Apache License, Version 2.0 (the "License"); you may not
  use this file except in compliance with the License.
  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.  For additional information regarding
  copyright in this work, please see the NOTICE file in the top level
  directory of this distribution.
--%>

.pressroom-page-title {
    margin-top: 0px;
    margin-bottom: 2em;
    text-align: center;
}

/* Reader sign-in on (0.18.0): one line under the heading, then the
   provider's button. The button follows Google's sign-in branding (white,
   a 1px #747775 rule, #1F1F1F text, the G at 18px) in light and dark alike;
   another provider gets the same quiet white button with a neutral mark. */
.login-reader-tagline {
    margin: -1.5em 0 1.5em;
    text-align: center;
    color: var(--ink-soft);
}
.sso-continue {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 10px;
    width: 100%;
    min-height: 44px;
    box-sizing: border-box;
    padding: 0 12px;
    background: #FFFFFF;
    color: #1F1F1F;
    border: 1px solid #747775;
    border-radius: 4px;
    font-family: Roboto, Arial, sans-serif;
    font-size: 14px;
    font-weight: 500;
    text-decoration: none;
}
.sso-continue:hover,
.sso-continue:focus {
    color: #1F1F1F;
    background: #F8FAFA;
    box-shadow: 0 1px 2px rgba(60, 64, 67, .3);
}
.sso-continue:focus-visible {
    outline: 2px solid var(--focus);
    outline-offset: 2px;
}
.sso-continue .g-signin-mark {
    flex: none;
}

/* The password form, collapsed under a caps label like the page's other
   section heads; one click opens it. */
.author-sign-in {
    margin-top: 28px;
    padding-top: 16px;
    border-top: 1px solid var(--line);
}
.author-sign-in > summary {
    cursor: pointer;
    font-family: var(--font-ui);
    font-size: 12px;
    font-weight: 600;
    letter-spacing: .08em;
    text-transform: uppercase;
    color: var(--ink-soft);
}
.author-sign-in[open] > summary {
    margin-bottom: 12px;
}
