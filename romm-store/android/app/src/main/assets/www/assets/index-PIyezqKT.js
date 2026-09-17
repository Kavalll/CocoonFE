(function(){const e=document.createElement("link").relList;if(e&&e.supports&&e.supports("modulepreload"))return;for(const a of document.querySelectorAll('link[rel="modulepreload"]'))n(a);new MutationObserver(a=>{for(const r of a)if(r.type==="childList")for(const l of r.addedNodes)l.tagName==="LINK"&&l.rel==="modulepreload"&&n(l)}).observe(document,{childList:!0,subtree:!0});function o(a){const r={};return a.integrity&&(r.integrity=a.integrity),a.referrerPolicy&&(r.referrerPolicy=a.referrerPolicy),a.crossOrigin==="use-credentials"?r.credentials="include":a.crossOrigin==="anonymous"?r.credentials="omit":r.credentials="same-origin",r}function n(a){if(a.ep)return;a.ep=!0;const r=o(a);fetch(a.href,r)}})();function y(t){return(t??"").trim().toLowerCase()}function X(t){const e=new Map,o=new Map;for(const n of t.platforms){e.set(y(n.cocoonUniqueId),n);for(const a of n.rommSlugs){const r=y(a);r&&!o.has(r)&&o.set(r,n)}for(const a of n.folderAliases){const r=y(a);r&&!o.has(r)&&o.set(r,n)}}return{byCocoonId:e,byRommSlug:o}}function Z(t,e,o=e){const{byRommSlug:n}=X(t);return n.get(y(e))??n.get(y(o))??null}function ee(t){const e=new Set,o=[];for(const n of t){const a=y(n);!a||e.has(a)||(e.add(a),o.push(a))}return o}function K(t,e){const o=e.layout??t.defaultLayout??"cocoon",n=Z(t,e.rommSlug,e.rommFsSlug),a=y(e.rommFsSlug)||y(e.rommSlug)||"roms",r=ee([n?.cocoonUniqueId,n?.cocoonShortname,...n?.folderAliases??[],e.rommSlug,e.rommFsSlug]),l=n?.cocoonUniqueId??a,i=e.overrides?.[l];if(i)return{cocoonUniqueId:n?.cocoonUniqueId??null,cocoonName:n?.cocoonName??null,folderName:i,mapped:!!n,candidates:r};if(o==="alias"&&e.existingFolders?.length){const C=new Set(e.existingFolders.map(y)),x=r.find(O=>C.has(O));if(x)return{cocoonUniqueId:n?.cocoonUniqueId??null,cocoonName:n?.cocoonName??null,folderName:x,mapped:!!n,candidates:r}}const S=o==="romm"||!n?a:n.cocoonUniqueId;return{cocoonUniqueId:n?.cocoonUniqueId??null,cocoonName:n?.cocoonName??null,folderName:S,mapped:!!n,candidates:r}}function te(t){return t.replace(/[\\/:*?"<>|\u0000-\u001f]/g,"_").trim()||"rom.bin"}function oe(t,e){return`${t.replace(/\\/g,"/").replace(/^\/+|\/+$/g,"")}/${te(e)}`}function ae(t){return t.trim().replace(/\/+$/,"")}function ne(t){const e=t.trim();if(!e)throw new Error("Server URL is required.");const o=/^https?:\/\//i.test(e)?e:`http://${e}`;return ae(o)}class F extends Error{status;constructor(e,o){super(o),this.status=e,this.name="RommApiError"}}class G{baseUrl;auth;fetchImpl;constructor(e){this.baseUrl=ne(e.baseUrl),this.auth=e.auth??{kind:"none"},this.fetchImpl=e.fetchImpl??fetch.bind(globalThis)}setAuth(e){this.auth=e}authHeaders(){if(this.auth.kind==="bearer")return{Authorization:`Bearer ${this.auth.token}`};if(this.auth.kind==="basic"){const e=`${this.auth.username}:${this.auth.password}`;return{Authorization:`Basic ${btoa(e)}`}}return{}}async request(e,o={}){const n=new Headers(o.headers);for(const[l,i]of Object.entries(this.authHeaders()))n.has(l)||n.set(l,i);const a=await this.fetchImpl(`${this.baseUrl}${e}`,{...o,headers:n});if(!a.ok){let l=`${a.status} ${a.statusText}`;try{const i=await a.json();i?.detail&&(l=typeof i.detail=="string"?i.detail:JSON.stringify(i.detail))}catch{try{const i=await a.text();i&&(l=i.slice(0,300))}catch{}}throw new F(a.status,l)}return a.status===204?void 0:(a.headers.get("content-type")||"").includes("application/json")?await a.json():await a.text()}heartbeat(){return this.request("/api/heartbeat")}platforms(){return this.request("/api/platforms")}async login(e,o,n="roms.read platforms.read"){const a=new URLSearchParams({grant_type:"password",username:e,password:o,scope:n}),r=await this.request("/api/token",{method:"POST",headers:{"Content-Type":"application/x-www-form-urlencoded"},body:a});return this.setAuth({kind:"bearer",token:r.access_token,refreshToken:r.refresh_token,expiresAt:Date.now()+r.expires*1e3}),r}async refresh(){if(this.auth.kind!=="bearer"||!this.auth.refreshToken)return null;const e=new URLSearchParams({grant_type:"refresh_token",refresh_token:this.auth.refreshToken}),o=await this.request("/api/token",{method:"POST",headers:{"Content-Type":"application/x-www-form-urlencoded"},body:e});return this.setAuth({kind:"bearer",token:o.access_token,refreshToken:o.refresh_token??this.auth.refreshToken,expiresAt:Date.now()+o.expires*1e3}),o}async exchangePairCode(e){const o=await this.request("/api/client-tokens/exchange",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({code:e.trim()})});return this.setAuth({kind:"bearer",token:o.raw_token}),o.raw_token}setClientToken(e){this.setAuth({kind:"bearer",token:e.trim()})}roms(e={}){const o=new URLSearchParams;return o.set("limit",String(e.limit??48)),o.set("offset",String(e.offset??0)),o.set("order_by","name"),o.set("order_dir","asc"),o.set("with_char_index","false"),o.set("with_filter_values","false"),o.set("with_rom_id_index","false"),o.set("with_files",e.withFiles?"true":"false"),e.platformId!=null&&o.append("platform_ids",String(e.platformId)),e.search&&o.set("search_term",e.search),this.request(`/api/roms?${o.toString()}`)}rom(e){return this.request(`/api/roms/${e}`)}downloadUrl(e,o){return`${this.baseUrl}/api/roms/${e}/content/${encodeURIComponent(o)}`}coverUrl(e){if(e.url_cover)return e.url_cover.startsWith("http")?e.url_cover:`${this.baseUrl}${e.url_cover}`;const o=e.path_cover_small||e.path_cover_large;return o?o.startsWith("http")?o:`${this.baseUrl}${o.startsWith("/")?o:`/${o}`}`:null}async downloadBlob(e,o,n){const a=new Headers(this.authHeaders()),r=await this.fetchImpl(this.downloadUrl(e,o),{headers:a});if(!r.ok)throw new F(r.status,`Download failed (${r.status})`);const l=Number(r.headers.get("content-length")||"")||null;if(!r.body||!n)return await r.blob();const i=r.body.getReader(),S=[];let C=0;for(;;){const{done:x,value:O}=await i.read();if(x)break;S.push(O),C+=O.byteLength,n(C,l)}return new Blob(S)}}const W="cocoon-romm-store:v1",P={baseUrl:"",auth:{kind:"none"},layout:"cocoon",folderOverrides:{},downloaded:{}};function re(){try{const t=localStorage.getItem(W);if(!t)return{...P,folderOverrides:{},downloaded:{}};const e=JSON.parse(t);return{...P,...e,folderOverrides:e.folderOverrides??{},downloaded:e.downloaded??{}}}catch{return{...P,folderOverrides:{},downloaded:{}}}}function se(t){localStorage.setItem(W,JSON.stringify(t))}function U(t,e){return`${t}:${e}`}function E(){return window.CocoonRomm??null}async function ie(){const t=window.showDirectoryPicker;return t?await t({mode:"readwrite"}):null}async function le(t,e,o){const n=e.split("/").filter(Boolean),a=n.pop();if(!a)throw new Error("Missing file name.");let r=t;for(const S of n)r=await r.getDirectoryHandle(S,{create:!0});const i=await(await r.getFileHandle(a,{create:!0})).createWritable();return await i.write(o),await i.close(),e}async function ce(t){const e=[],o=t;if(typeof o[Symbol.asyncIterator]!="function")return e;for await(const[n,a]of o)a.kind==="directory"&&e.push(n);return e}function de(t,e){const o=URL.createObjectURL(t),n=document.createElement("a");n.href=o,n.download=e,document.body.appendChild(n),n.click(),n.remove(),setTimeout(()=>URL.revokeObjectURL(o),1e4)}const d=document.querySelector("#app"),J=await fetch("./platform-map.json").then(t=>t.json());let s=re(),f=new G({baseUrl:s.baseUrl||"https://demo.romm.app",auth:s.auth}),$=null,M=[],m=s.auth.kind==="none"&&!s.baseUrl?"login":"platforms",R=[],_=null,w=[],I=0,T=0,L="",j=null,h="",u="",v=0,g=!1;const ue="cocoon-romm-store";async function Y(){return await new Promise((t,e)=>{const o=indexedDB.open(ue,1);o.onupgradeneeded=()=>o.result.createObjectStore("fs"),o.onsuccess=()=>t(o.result),o.onerror=()=>e(o.error)})}async function fe(t){const e=await Y();await new Promise((o,n)=>{const a=e.transaction("fs","readwrite");t?a.objectStore("fs").put(t,"romRoot"):a.objectStore("fs").delete("romRoot"),a.oncomplete=()=>o(),a.onerror=()=>n(a.error)})}async function me(){try{const t=await Y(),e=await new Promise((a,r)=>{const i=t.transaction("fs","readonly").objectStore("fs").get("romRoot");i.onsuccess=()=>a(i.result),i.onerror=()=>r(i.error)});return e&&(await e.queryPermission({mode:"readwrite"})==="granted"||await e.requestPermission({mode:"readwrite"})==="granted")?e:null}catch{return null}}function k(){se(s)}function b(t){return t<1024?`${t} B`:t<1024*1024?`${(t/1024).toFixed(1)} KB`:t<1024*1024*1024?`${(t/(1024*1024)).toFixed(1)} MB`:`${(t/(1024*1024*1024)).toFixed(2)} GB`}function Q(t){return K(J,{rommSlug:t.platform_slug,rommFsSlug:t.platform_fs_slug,layout:s.layout,existingFolders:M,overrides:s.folderOverrides})}function V(t){return!!s.downloaded[U(t.id,t.fs_name)]}async function N(){const t=E();if(t?.listFolders){M=await t.listFolders();return}$&&(M=await ce($))}async function H(t){f=new G({baseUrl:t,auth:s.auth}),await f.heartbeat(),s.baseUrl=f.baseUrl,k()}async function B(){g=!0,u="",c();try{await N(),R=(await f.platforms()).sort((t,e)=>(t.display_name||t.name).localeCompare(e.display_name||e.name)),m="platforms"}catch(t){u=t instanceof Error?t.message:String(t),m="login"}finally{g=!1,c()}}async function A(t=!0){if(_){t&&(w=[],T=0),g=!0,u="",c();try{const e=await f.roms({platformId:_.id,search:L.trim()||void 0,limit:48,offset:T});w=t?e.items:[...w,...e.items],I=e.total??w.length,T=w.length,m="games"}catch(e){u=e instanceof Error?e.message:String(e)}finally{g=!1,c()}}}async function pe(t){g=!0,u="",h="Starting download…",v=0,c();try{const e=Q(t),o=oe(e.folderName,t.fs_name),n=E();if(n?.download){h="Downloading through the Android folder bridge…",c();const a=await n.download(f.downloadUrl(t.id,t.fs_name),o,f.authHeaders().Authorization);s.downloaded[U(t.id,t.fs_name)]={path:a,at:Date.now()},h=`Saved to ${a}. Rescan this platform in Cocoon if it does not appear immediately.`}else if(n?.writeFile){const a=await f.downloadBlob(t.id,t.fs_name,(l,i)=>{v=i?Math.round(l/i*100):50,h=`Downloading ${b(l)}${i?` / ${b(i)}`:""}`,c()}),r=await n.writeFile(o,await a.arrayBuffer());s.downloaded[U(t.id,t.fs_name)]={path:r,at:Date.now()},h=`Saved to ${r}. Rescan this platform in Cocoon if it does not appear immediately.`}else if($){const a=await f.downloadBlob(t.id,t.fs_name,(l,i)=>{v=i?Math.round(l/i*100):50,h=`Downloading ${b(l)}${i?` / ${b(i)}`:""}`,c()}),r=await le($,o,a);s.downloaded[U(t.id,t.fs_name)]={path:r,at:Date.now()},h=`Saved to ${r}. Rescan this platform in Cocoon if it does not appear immediately.`}else{const a=await f.downloadBlob(t.id,t.fs_name,(r,l)=>{v=l?Math.round(r/l*100):50,h=`Downloading ${b(r)}${l?` / ${b(l)}`:""}`,c()});de(a,t.fs_name),s.downloaded[U(t.id,t.fs_name)]={path:t.fs_name,at:Date.now()},h=`Downloaded ${t.fs_name}. Move it into your Cocoon ${e.folderName} folder, or choose a ROM root in Settings so files land there automatically.`}k(),v=100}catch(e){u=e instanceof Error?e.message:String(e),h=""}finally{g=!1,c()}}function q(t){return`
    <header class="topbar">
      <div class="brand">
        <strong>Cocoon RomM Store</strong>
        <span>${t}</span>
      </div>
      ${m!=="login"?`<input class="search" id="search" placeholder="Search this library" value="${p(L)}" />`:""}
      ${m!=="login"?'<button class="ghost" data-go="settings">Settings</button>':""}
    </header>
  `}function p(t){return t.replaceAll("&","&amp;").replaceAll("<","&lt;").replaceAll(">","&gt;").replaceAll('"',"&quot;")}function z(t){return t?`style="background-image:url('${t.replaceAll("'","%27")}')"`:""}function he(){d.innerHTML=`
    ${q("Connect the RomM server you already have running.")}
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
      ${u?`<p class="error">${p(u)}</p>`:""}
      ${g?'<p class="muted">Connecting…</p>':""}
    </section>
  `,d.querySelector("#connect")?.addEventListener("click",we),d.querySelector("#kiosk")?.addEventListener("click",ge)}async function ge(){const t=d.querySelector("#baseUrl").value;g=!0,u="",c();try{s.auth={kind:"none"},k(),await H(t),await B()}catch(e){u=e instanceof Error?e.message:String(e),g=!1,c()}}async function we(){const t=d.querySelector("#baseUrl").value,e=d.querySelector("#username").value.trim(),o=d.querySelector("#password").value,n=d.querySelector("#token").value.trim();g=!0,u="",c();try{await H(t),n.startsWith("rmm_")?(f.setClientToken(n),s.auth=f.auth):/^\d{8}$/.test(n)?(await f.exchangePairCode(n),s.auth=f.auth):e&&o?(await f.login(e,o),s.auth=f.auth):(s.auth={kind:"none"},f.setAuth(s.auth)),k(),await B()}catch(a){if(a instanceof F&&a.status===401&&e&&o){f.setAuth({kind:"basic",username:e,password:o}),s.auth=f.auth,k();try{await B();return}catch(r){u=r instanceof Error?r.message:String(r)}}else u=a instanceof Error?a.message:String(a);g=!1,m="login",c()}}function ye(t,e){const o=K(J,{rommSlug:t,rommFsSlug:e,layout:s.layout,existingFolders:M,overrides:s.folderOverrides});return o.mapped?`<span class="badge ok">${o.cocoonName} · ${o.folderName}</span>`:`<span class="badge warn">unmapped · ${o.folderName}</span>`}function be(){d.innerHTML=`
    ${q(s.baseUrl||"Not connected")}
    ${u?`<p class="error">${p(u)}</p>`:""}
    <div class="grid">
      ${R.map(t=>`
        <button class="card" data-platform="${t.id}">
          <div class="art" ${z(t.url_logo||null)}>${ye(t.slug,t.fs_slug)}</div>
          <div class="meta">
            <h3>${p(t.display_name||t.name)}</h3>
            <p>${t.rom_count} games</p>
          </div>
        </button>
      `).join("")}
    </div>
    ${R.length===0&&!g?'<p class="empty">No platforms came back from RomM.</p>':""}
  `,D(),d.querySelectorAll("[data-platform]").forEach(t=>{t.addEventListener("click",()=>{_=R.find(e=>String(e.id)===t.dataset.platform)??null,L="",A(!0)})})}function ve(){const t=_?.display_name||_?.name||"Games";d.innerHTML=`
    ${q(t)}
    <div class="row" style="margin-bottom:1rem">
      <button class="ghost" data-go="platforms">All platforms</button>
      <span class="muted">${w.length} of ${I||w.length}</span>
    </div>
    ${u?`<p class="error">${p(u)}</p>`:""}
    <div class="grid">
      ${w.map(e=>`
        <button class="card" data-rom="${e.id}">
          <div class="art" ${z(f.coverUrl(e))}>
            ${V(e)?'<span class="badge ok">on device</span>':`<span class="badge">${b(e.fs_size_bytes)}</span>`}
          </div>
          <div class="meta">
            <h3>${p(e.name||e.fs_name_no_tags)}</h3>
            <p>${p(e.fs_name)}</p>
          </div>
        </button>
      `).join("")}
    </div>
    ${w.length<I?'<div class="row" style="margin-top:1rem"><button id="more">Load more</button></div>':""}
  `,D(),d.querySelector("[data-go='platforms']")?.addEventListener("click",()=>{m="platforms",c()}),d.querySelector("#more")?.addEventListener("click",()=>{A(!1)}),d.querySelectorAll("[data-rom]").forEach(e=>{e.addEventListener("click",()=>{j=w.find(o=>String(o.id)===e.dataset.rom)??null,h="",v=0,m="detail",c()})})}function $e(){if(!j){m="games",c();return}const t=j,e=Q(t);d.innerHTML=`
    ${q(t.name||t.fs_name_no_tags)}
    <div class="row" style="margin-bottom:1rem">
      <button class="ghost" data-go="games">Back</button>
    </div>
    <article class="detail">
      <div class="art" ${z(f.coverUrl(t))}></div>
      <div class="copy">
        <h2>${p(t.name||t.fs_name_no_tags)}</h2>
        <p>${p(t.platform_display_name)} · ${p(t.fs_name)} · ${b(t.fs_size_bytes)}</p>
        <p style="margin-top:.8rem">${p(t.summary||"No description from RomM.")}</p>
        <p style="margin-top:.8rem">Cocoon folder: <strong>${p(e.folderName)}</strong> ${e.mapped?"":"(no platform map; using RomM slug)"}</p>
        <div class="progress"><span style="width:${v}%"></span></div>
        ${h?`<p class="muted">${p(h)}</p>`:""}
        ${u?`<p class="error">${p(u)}</p>`:""}
        <div class="row" style="margin-top:1rem">
          <button class="primary" id="download" ${g?"disabled":""}>${V(t)?"Download again":"Download to Cocoon"}</button>
        </div>
      </div>
    </article>
  `,D(),d.querySelector("[data-go='games']")?.addEventListener("click",()=>{m="games",c()}),d.querySelector("#download")?.addEventListener("click",()=>{pe(t)})}function ke(){d.innerHTML=`
    ${q("Settings")}
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
      <p class="muted" style="margin-top:1rem">${$?"ROM root selected in this browser.":E()?.getRomRoot?"Native folder access available.":"No ROM root yet — downloads will go through the browser download manager."}</p>
      ${u?`<p class="error">${p(u)}</p>`:""}
    </section>
  `,D(),d.querySelector("#layout")?.addEventListener("change",t=>{s.layout=t.target.value,k()}),d.querySelector("#pick")?.addEventListener("click",async()=>{const t=E();if(t?.pickRomRoot){const o=await t.pickRomRoot();h=o?`ROM root: ${o}`:"Folder picker cancelled.",await N(),c();return}const e=await ie();if(!e){u="This browser cannot pick a folder. Pin the Android wrapper or use Chrome, then move files into your Cocoon ROM folders.",c();return}$=e,await fe(e),await N(),u="",c()}),d.querySelector("#logout")?.addEventListener("click",()=>{s.auth={kind:"none"},s.baseUrl="",k(),m="login",c()}),d.querySelector("[data-go='platforms']")?.addEventListener("click",()=>{m="platforms",c()})}function D(){const t=d.querySelector("#search");t?.addEventListener("keydown",e=>{if(e.key==="Enter")if(L=t.value,m==="games"||m==="detail")A(!0);else{const o=L.trim().toLowerCase(),n=R.find(a=>(a.display_name||a.name).toLowerCase().includes(o));n&&(_=n,A(!0))}}),d.querySelector("[data-go='settings']")?.addEventListener("click",()=>{m="settings",c()})}function c(){m==="login"?he():m==="settings"?ke():m==="detail"?$e():m==="games"?ve():be()}$=await me();await N();if(s.baseUrl)try{await H(s.baseUrl),await B()}catch{m="login",c()}else c();
