(function(){const e=document.createElement("link").relList;if(e&&e.supports&&e.supports("modulepreload"))return;for(const a of document.querySelectorAll('link[rel="modulepreload"]'))n(a);new MutationObserver(a=>{for(const r of a)if(r.type==="childList")for(const m of r.addedNodes)m.tagName==="LINK"&&m.rel==="modulepreload"&&n(m)}).observe(document,{childList:!0,subtree:!0});function o(a){const r={};return a.integrity&&(r.integrity=a.integrity),a.referrerPolicy&&(r.referrerPolicy=a.referrerPolicy),a.crossOrigin==="use-credentials"?r.credentials="include":a.crossOrigin==="anonymous"?r.credentials="omit":r.credentials="same-origin",r}function n(a){if(a.ep)return;a.ep=!0;const r=o(a);fetch(a.href,r)}})();function y(t){return(t??"").trim().toLowerCase()}function te(t){const e=new Map,o=new Map;for(const n of t.platforms){e.set(y(n.cocoonUniqueId),n);for(const a of n.rommSlugs){const r=y(a);r&&!o.has(r)&&o.set(r,n)}for(const a of n.folderAliases){const r=y(a);r&&!o.has(r)&&o.set(r,n)}}return{byCocoonId:e,byRommSlug:o}}function oe(t,e,o=e){const{byRommSlug:n}=te(t);return n.get(y(e))??n.get(y(o))??null}function ae(t){const e=new Set,o=[];for(const n of t){const a=y(n);!a||e.has(a)||(e.add(a),o.push(a))}return o}function J(t,e){const o=e.layout??t.defaultLayout??"cocoon",n=oe(t,e.rommSlug,e.rommFsSlug),a=y(e.rommFsSlug)||y(e.rommSlug)||"roms",r=ae([n?.cocoonUniqueId,n?.cocoonShortname,...n?.folderAliases??[],e.rommSlug,e.rommFsSlug]),m=n?.cocoonUniqueId??a,d=e.overrides?.[m];if(d)return{cocoonUniqueId:n?.cocoonUniqueId??null,cocoonName:n?.cocoonName??null,folderName:d,mapped:!!n,candidates:r};if(o==="alias"&&e.existingFolders?.length){const q=new Set(e.existingFolders.map(y)),C=r.find(O=>q.has(O));if(C)return{cocoonUniqueId:n?.cocoonUniqueId??null,cocoonName:n?.cocoonName??null,folderName:C,mapped:!!n,candidates:r}}const $=o==="romm"||!n?a:n.cocoonUniqueId;return{cocoonUniqueId:n?.cocoonUniqueId??null,cocoonName:n?.cocoonName??null,folderName:$,mapped:!!n,candidates:r}}function ne(t){return t.replace(/[\\/:*?"<>|\u0000-\u001f]/g,"_").trim()||"rom.bin"}function re(t,e){return`${t.replace(/\\/g,"/").replace(/^\/+|\/+$/g,"")}/${ne(e)}`}function se(t){return t.trim().replace(/\/+$/,"")}function ie(t){const e=t.trim();if(!e)throw new Error("Server URL is required.");const o=/^https?:\/\//i.test(e)?e:`http://${e}`;return se(o)}class I extends Error{status;constructor(e,o){super(o),this.status=e,this.name="RommApiError"}}class Y{baseUrl;auth;fetchImpl;constructor(e){this.baseUrl=ie(e.baseUrl),this.auth=e.auth??{kind:"none"},this.fetchImpl=e.fetchImpl??fetch.bind(globalThis)}setAuth(e){this.auth=e}authHeaders(){if(this.auth.kind==="bearer")return{Authorization:`Bearer ${this.auth.token}`};if(this.auth.kind==="basic"){const e=`${this.auth.username}:${this.auth.password}`;return{Authorization:`Basic ${btoa(e)}`}}return{}}async request(e,o={}){const n=new Headers(o.headers);for(const[m,d]of Object.entries(this.authHeaders()))n.has(m)||n.set(m,d);const a=await this.fetchImpl(`${this.baseUrl}${e}`,{...o,headers:n});if(!a.ok){let m=`${a.status} ${a.statusText}`;try{const d=await a.json();d?.detail&&(m=typeof d.detail=="string"?d.detail:JSON.stringify(d.detail))}catch{try{const d=await a.text();d&&(m=d.slice(0,300))}catch{}}throw new I(a.status,m)}return a.status===204?void 0:(a.headers.get("content-type")||"").includes("application/json")?await a.json():await a.text()}heartbeat(){return this.request("/api/heartbeat")}platforms(){return this.request("/api/platforms")}async login(e,o,n="roms.read platforms.read"){const a=new URLSearchParams({grant_type:"password",username:e,password:o,scope:n}),r=await this.request("/api/token",{method:"POST",headers:{"Content-Type":"application/x-www-form-urlencoded"},body:a});return this.setAuth({kind:"bearer",token:r.access_token,refreshToken:r.refresh_token,expiresAt:Date.now()+r.expires*1e3}),r}async refresh(){if(this.auth.kind!=="bearer"||!this.auth.refreshToken)return null;const e=new URLSearchParams({grant_type:"refresh_token",refresh_token:this.auth.refreshToken}),o=await this.request("/api/token",{method:"POST",headers:{"Content-Type":"application/x-www-form-urlencoded"},body:e});return this.setAuth({kind:"bearer",token:o.access_token,refreshToken:o.refresh_token??this.auth.refreshToken,expiresAt:Date.now()+o.expires*1e3}),o}async exchangePairCode(e){const o=await this.request("/api/client-tokens/exchange",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({code:e.trim()})});return this.setAuth({kind:"bearer",token:o.raw_token}),o.raw_token}setClientToken(e){this.setAuth({kind:"bearer",token:e.trim()})}roms(e={}){const o=new URLSearchParams;return o.set("limit",String(e.limit??48)),o.set("offset",String(e.offset??0)),o.set("order_by","name"),o.set("order_dir","asc"),o.set("with_char_index","false"),o.set("with_filter_values","false"),o.set("with_rom_id_index","false"),o.set("with_files",e.withFiles?"true":"false"),e.platformId!=null&&o.append("platform_ids",String(e.platformId)),e.search&&o.set("search_term",e.search),this.request(`/api/roms?${o.toString()}`)}rom(e){return this.request(`/api/roms/${e}`)}downloadUrl(e,o){return`${this.baseUrl}/api/roms/${e}/content/${encodeURIComponent(o)}`}coverUrl(e){if(e.url_cover)return e.url_cover.startsWith("http")?e.url_cover:`${this.baseUrl}${e.url_cover}`;const o=e.path_cover_small||e.path_cover_large;return o?o.startsWith("http")?o:`${this.baseUrl}${o.startsWith("/")?o:`/${o}`}`:null}async downloadBlob(e,o,n){const a=new Headers(this.authHeaders()),r=await this.fetchImpl(this.downloadUrl(e,o),{headers:a});if(!r.ok)throw new I(r.status,`Download failed (${r.status})`);const m=Number(r.headers.get("content-length")||"")||null;if(!r.body||!n)return await r.blob();const d=r.body.getReader(),$=[];let q=0;for(;;){const{done:C,value:O}=await d.read();if(C)break;$.push(O),q+=O.byteLength,n(q,m)}return new Blob($)}}const Q="cocoon-romm-store:v1",F={baseUrl:"",auth:{kind:"none"},layout:"cocoon",folderOverrides:{},downloaded:{}};function le(){try{const t=localStorage.getItem(Q);if(!t)return{...F,folderOverrides:{},downloaded:{}};const e=JSON.parse(t);return{...F,...e,folderOverrides:e.folderOverrides??{},downloaded:e.downloaded??{}}}catch{return{...F,folderOverrides:{},downloaded:{}}}}function ce(t){localStorage.setItem(Q,JSON.stringify(t))}function k(t,e){return`${t}:${e}`}function E(){return window.CocoonRomm??null}async function de(){const t=window.showDirectoryPicker;return t?await t({mode:"readwrite"}):null}async function ue(t,e,o){const n=e.split("/").filter(Boolean),a=n.pop();if(!a)throw new Error("Missing file name.");let r=t;for(const $ of n)r=await r.getDirectoryHandle($,{create:!0});const d=await(await r.getFileHandle(a,{create:!0})).createWritable();return await d.write(o),await d.close(),e}async function fe(t){const e=[],o=t;if(typeof o[Symbol.asyncIterator]!="function")return e;for await(const[n,a]of o)a.kind==="directory"&&e.push(n);return e}function me(t,e){const o=URL.createObjectURL(t),n=document.createElement("a");n.href=o,n.download=e,document.body.appendChild(n),n.click(),n.remove(),setTimeout(()=>URL.revokeObjectURL(o),1e4)}function pe(t,e){const o=document.createElement("a");o.href=t,o.download=e,o.rel="noopener",o.target="_blank",document.body.appendChild(o),o.click(),o.remove()}function G(t){if(!(t instanceof Error))return!1;const e=t.message.toLowerCase();return e.includes("failed to fetch")||e.includes("networkerror")||e.includes("network error")||e.includes("blocked by cors")||e.includes("access-control-allow-origin")}const i=document.querySelector("#app"),V=await fetch("./platform-map.json").then(t=>t.json());let s=le(),u=new Y({baseUrl:s.baseUrl||"https://demo.romm.app",auth:s.auth}),b=null,x=[],f=s.auth.kind==="none"&&!s.baseUrl?"login":"platforms",R=[],S=null,g=[],j=0,T=0,U="",H=null,h="",c="",_=0,w=!1;const he="cocoon-romm-store";async function X(){return await new Promise((t,e)=>{const o=indexedDB.open(he,1);o.onupgradeneeded=()=>o.result.createObjectStore("fs"),o.onsuccess=()=>t(o.result),o.onerror=()=>e(o.error)})}async function we(t){const e=await X();await new Promise((o,n)=>{const a=e.transaction("fs","readwrite");t?a.objectStore("fs").put(t,"romRoot"):a.objectStore("fs").delete("romRoot"),a.oncomplete=()=>o(),a.onerror=()=>n(a.error)})}async function ge(){try{const t=await X(),e=await new Promise((a,r)=>{const d=t.transaction("fs","readonly").objectStore("fs").get("romRoot");d.onsuccess=()=>a(d.result),d.onerror=()=>r(d.error)});return e&&(await e.queryPermission({mode:"readwrite"})==="granted"||await e.requestPermission({mode:"readwrite"})==="granted")?e:null}catch{return null}}function v(){ce(s)}function M(t){return t<1024?`${t} B`:t<1024*1024?`${(t/1024).toFixed(1)} KB`:t<1024*1024*1024?`${(t/(1024*1024)).toFixed(1)} MB`:`${(t/(1024*1024*1024)).toFixed(2)} GB`}function Z(t){return J(V,{rommSlug:t.platform_slug,rommFsSlug:t.platform_fs_slug,layout:s.layout,existingFolders:x,overrides:s.folderOverrides})}function ee(t){return!!s.downloaded[k(t.id,t.fs_name)]}async function N(){const t=E();if(t?.listFolders){x=await t.listFolders();return}b&&(x=await fe(b))}async function z(t){u=new Y({baseUrl:t,auth:s.auth}),await u.heartbeat(),s.baseUrl=u.baseUrl,v()}async function D(){w=!0,c="",l();try{await N(),R=(await u.platforms()).sort((t,e)=>(t.display_name||t.name).localeCompare(e.display_name||e.name)),f="platforms"}catch(t){c=t instanceof Error?t.message:String(t),f="login"}finally{w=!1,l()}}async function B(t=!0){if(S){t&&(g=[],T=0),w=!0,c="",l();try{const e=await u.roms({platformId:S.id,search:U.trim()||void 0,limit:48,offset:T});g=t?e.items:[...g,...e.items],j=e.total??g.length,T=g.length,f="games"}catch(e){c=e instanceof Error?e.message:String(e)}finally{w=!1,l()}}}async function P(t){return await u.downloadBlob(t.id,t.fs_name,(e,o)=>{_=o?Math.round(e/o*100):50,h=`Downloading ${M(e)}${o?` / ${M(o)}`:""}`,l()})}function W(t,e,o){pe(u.downloadUrl(t.id,t.fs_name),t.fs_name),s.downloaded[k(t.id,t.fs_name)]={path:t.fs_name,at:Date.now()},_=100,h=`Browser download started for ${t.fs_name}. Move it into your Cocoon ${e} folder. ${o}`}async function ye(t){w=!0,c="",h="Starting download…",_=0,l();try{const e=Z(t),o=re(e.folderName,t.fs_name),n=E();if(n?.download){h="Downloading through the Android folder bridge…",l();const a=await n.download(u.downloadUrl(t.id,t.fs_name),o,u.authHeaders().Authorization);s.downloaded[k(t.id,t.fs_name)]={path:a,at:Date.now()},h=`Saved to ${a}. Rescan this platform in Cocoon if it does not appear immediately.`}else if(n?.writeFile){const a=await P(t),r=await n.writeFile(o,await a.arrayBuffer());s.downloaded[k(t.id,t.fs_name)]={path:r,at:Date.now()},h=`Saved to ${r}. Rescan this platform in Cocoon if it does not appear immediately.`}else if(b)try{const a=await P(t),r=await ue(b,o,a);s.downloaded[k(t.id,t.fs_name)]={path:r,at:Date.now()},h=`Saved to ${r}. Rescan this platform in Cocoon if it does not appear immediately.`}catch(a){if(!G(a))throw a;W(t,e.folderName,"The RomM download URL blocked in-page fetch (CORS). Direct folder write needs the Android wrapper.")}else try{const a=await P(t);me(a,t.fs_name),s.downloaded[k(t.id,t.fs_name)]={path:t.fs_name,at:Date.now()},h=`Downloaded ${t.fs_name}. Move it into your Cocoon ${e.folderName} folder, or choose a ROM root in Settings so files land there automatically.`}catch(a){if(!G(a))throw a;W(t,e.folderName,"RomM file downloads often omit CORS headers, so the file is opened directly instead.")}v(),_=100}catch(e){c=e instanceof Error?e.message:String(e),h=""}finally{w=!1,l()}}function L(t){return`
    <header class="topbar">
      <div class="brand">
        <strong>Cocoon RomM Store</strong>
        <span>${t}</span>
      </div>
      ${f!=="login"?`<input class="search" id="search" placeholder="Search this library" value="${p(U)}" />`:""}
      ${f!=="login"?'<button class="ghost" data-go="settings">Settings</button>':""}
    </header>
  `}function p(t){return t.replaceAll("&","&amp;").replaceAll("<","&lt;").replaceAll(">","&gt;").replaceAll('"',"&quot;")}function K(t){return t?`style="background-image:url('${t.replaceAll("'","%27")}')"`:""}function be(){i.innerHTML=`
    ${L("Connect the RomM server you already have running.")}
    <section class="panel">
      <h1>Connect RomM</h1>
      <p class="muted">Cocoon itself is closed source, so this companion lives in the dock and writes games into the same platform folders Cocoon scans.</p>
      <div class="field">
        <label for="baseUrl">RomM URL</label>
        <input id="baseUrl" value="${p(s.baseUrl||"https://demo.romm.app")}" placeholder="https://romm.example.com" />
      </div>
      <div class="field">
        <label for="username">Username</label>
        <input id="username" autocomplete="username" />
      </div>
      <div class="field">
        <label for="password">Password</label>
        <input id="password" type="password" autocomplete="current-password" />
      </div>
      <div class="field">
        <label for="token">Or paste a client token / 8-digit pairing code</label>
        <input id="token" placeholder="rmm_… or 12345678" />
      </div>
      <div class="row">
        <button class="primary" id="connect">Connect</button>
        <button class="ghost" id="kiosk">Browse without login</button>
      </div>
      ${c?`<p class="error">${p(c)}</p>`:""}
      ${w?'<p class="muted">Connecting…</p>':""}
    </section>
  `,i.querySelector("#connect")?.addEventListener("click",$e),i.querySelector("#kiosk")?.addEventListener("click",ve)}async function ve(){const t=i.querySelector("#baseUrl").value;w=!0,c="",l();try{s.auth={kind:"none"},v(),await z(t),await D()}catch(e){c=e instanceof Error?e.message:String(e),w=!1,l()}}async function $e(){const t=i.querySelector("#baseUrl").value,e=i.querySelector("#username").value.trim(),o=i.querySelector("#password").value,n=i.querySelector("#token").value.trim();w=!0,c="",l();try{await z(t),n.startsWith("rmm_")?(u.setClientToken(n),s.auth=u.auth):/^\d{8}$/.test(n)?(await u.exchangePairCode(n),s.auth=u.auth):e&&o?(await u.login(e,o),s.auth=u.auth):(s.auth={kind:"none"},u.setAuth(s.auth)),v(),await D()}catch(a){if(a instanceof I&&a.status===401&&e&&o){u.setAuth({kind:"basic",username:e,password:o}),s.auth=u.auth,v();try{await D();return}catch(r){c=r instanceof Error?r.message:String(r)}}else c=a instanceof Error?a.message:String(a);w=!1,f="login",l()}}function ke(t,e){const o=J(V,{rommSlug:t,rommFsSlug:e,layout:s.layout,existingFolders:x,overrides:s.folderOverrides});return o.mapped?`<span class="badge ok">${o.cocoonName} · ${o.folderName}</span>`:`<span class="badge warn">unmapped · ${o.folderName}</span>`}function Se(){i.innerHTML=`
    ${L(s.baseUrl||"Not connected")}
    ${c?`<p class="error">${p(c)}</p>`:""}
    <div class="grid">
      ${R.map(t=>`
        <button class="card" data-platform="${t.id}">
          <div class="art" ${K(t.url_logo||null)}>${ke(t.slug,t.fs_slug)}</div>
          <div class="meta">
            <h3>${p(t.display_name||t.name)}</h3>
            <p>${t.rom_count} games</p>
          </div>
        </button>
      `).join("")}
    </div>
    ${R.length===0&&!w?'<p class="empty">No platforms came back from RomM.</p>':""}
  `,A(),i.querySelectorAll("[data-platform]").forEach(t=>{t.addEventListener("click",()=>{S=R.find(e=>String(e.id)===t.dataset.platform)??null,U="",B(!0)})})}function _e(){const t=S?.display_name||S?.name||"Games";i.innerHTML=`
    ${L(t)}
    <div class="row" style="margin-bottom:1rem">
      <button class="ghost" data-go="platforms">All platforms</button>
      <span class="muted">${g.length} of ${j||g.length}</span>
    </div>
    ${c?`<p class="error">${p(c)}</p>`:""}
    <div class="grid">
      ${g.map(e=>`
        <button class="card" data-rom="${e.id}">
          <div class="art" ${K(u.coverUrl(e))}>
            ${ee(e)?'<span class="badge ok">on device</span>':`<span class="badge">${M(e.fs_size_bytes)}</span>`}
          </div>
          <div class="meta">
            <h3>${p(e.name||e.fs_name_no_tags)}</h3>
            <p>${p(e.fs_name)}</p>
          </div>
        </button>
      `).join("")}
    </div>
    ${g.length<j?'<div class="row" style="margin-top:1rem"><button id="more">Load more</button></div>':""}
  `,A(),i.querySelector("[data-go='platforms']")?.addEventListener("click",()=>{f="platforms",l()}),i.querySelector("#more")?.addEventListener("click",()=>{B(!1)}),i.querySelectorAll("[data-rom]").forEach(e=>{e.addEventListener("click",()=>{H=g.find(o=>String(o.id)===e.dataset.rom)??null,h="",_=0,f="detail",l()})})}function Re(){if(!H){f="games",l();return}const t=H,e=Z(t);i.innerHTML=`
    ${L(t.name||t.fs_name_no_tags)}
    <div class="row" style="margin-bottom:1rem">
      <button class="ghost" data-go="games">Back</button>
    </div>
    <article class="detail">
      <div class="art" ${K(u.coverUrl(t))}></div>
      <div class="copy">
        <h2>${p(t.name||t.fs_name_no_tags)}</h2>
        <p>${p(t.platform_display_name)} · ${p(t.fs_name)} · ${M(t.fs_size_bytes)}</p>
        <p style="margin-top:.8rem">${p(t.summary||"No description from RomM.")}</p>
        <p style="margin-top:.8rem">Cocoon folder: <strong>${p(e.folderName)}</strong> ${e.mapped?"":"(no platform map; using RomM slug)"}</p>
        <div class="progress"><span style="width:${_}%"></span></div>
        ${h?`<p class="muted">${p(h)}</p>`:""}
        ${c?`<p class="error">${p(c)}</p>`:""}
        <div class="row" style="margin-top:1rem">
          <button class="primary" id="download" ${w?"disabled":""}>${ee(t)?"Download again":"Download to Cocoon"}</button>
        </div>
      </div>
    </article>
  `,A(),i.querySelector("[data-go='games']")?.addEventListener("click",()=>{f="games",l()}),i.querySelector("#download")?.addEventListener("click",()=>{ye(t)})}function Ue(){i.innerHTML=`
    ${L("Settings")}
    <section class="panel">
      <div class="field">
        <label for="layout">Folder layout</label>
        <select id="layout">
          <option value="cocoon" ${s.layout==="cocoon"?"selected":""}>Cocoon unique IDs (gb, snes, n64, gc, n3ds)</option>
          <option value="romm" ${s.layout==="romm"?"selected":""}>RomM slugs (gb, snes, n64, ngc, 3ds)</option>
          <option value="alias" ${s.layout==="alias"?"selected":""}>Match folders that already exist</option>
        </select>
      </div>
      <p class="muted">Point this app at the same ROM root Cocoon scans. Games are saved into a per-platform subfolder, then show up after a library rescan.</p>
      <div class="row">
        <button class="primary" id="pick">Choose ROM root folder</button>
        <button class="ghost" data-go="platforms">Back to library</button>
        <button class="ghost" id="logout">Log out</button>
      </div>
      <p class="muted" style="margin-top:1rem">${b?"ROM root selected in this browser.":E()?.getRomRoot?"Native folder access available.":"No ROM root yet — downloads will go through the browser download manager."}</p>
      ${c?`<p class="error">${p(c)}</p>`:""}
    </section>
  `,A(),i.querySelector("#layout")?.addEventListener("change",t=>{s.layout=t.target.value,v()}),i.querySelector("#pick")?.addEventListener("click",async()=>{const t=E();if(t?.pickRomRoot){const o=await t.pickRomRoot();h=o?`ROM root: ${o}`:"Folder picker cancelled.",await N(),l();return}const e=await de();if(!e){c="This browser cannot pick a folder. Pin the Android wrapper or use Chrome, then move files into your Cocoon ROM folders.",l();return}b=e,await we(e),await N(),c="",l()}),i.querySelector("#logout")?.addEventListener("click",()=>{s.auth={kind:"none"},s.baseUrl="",v(),f="login",l()}),i.querySelector("[data-go='platforms']")?.addEventListener("click",()=>{f="platforms",l()})}function A(){const t=i.querySelector("#search");t?.addEventListener("keydown",e=>{if(e.key==="Enter")if(U=t.value,f==="games"||f==="detail")B(!0);else{const o=U.trim().toLowerCase(),n=R.find(a=>(a.display_name||a.name).toLowerCase().includes(o));n&&(S=n,B(!0))}}),i.querySelector("[data-go='settings']")?.addEventListener("click",()=>{f="settings",l()})}function l(){f==="login"?be():f==="settings"?Ue():f==="detail"?Re():f==="games"?_e():Se()}b=await ge();await N();if(s.baseUrl)try{await z(s.baseUrl),await D()}catch{f="login",l()}else l();
