(()=>{ 
  const getOpenMenus=()=>Array.from(document.querySelectorAll('.nav-tools[open]'));
  document.addEventListener('click',event=>{
    getOpenMenus().forEach(menu=>{
      if(!menu.contains(event.target)) menu.open=false;
    });
  });
  document.addEventListener('keydown',event=>{
    if(event.key!=='Escape')return;
    const menus=getOpenMenus();
    if(!menus.length)return;
    menus.forEach(menu=>menu.open=false);
    menus[menus.length-1].querySelector('summary')?.focus();
  });
})();
(()=>{
  const normalizePath=pathname=>pathname.replace(/index\.html$/i,'').replace(/\/+$/,'')||'/';
  const currentPath=normalizePath(location.pathname);
  document.querySelectorAll('#primary-navigation').forEach(nav=>{
    const links=Array.from(nav.querySelectorAll('a'));
    links.forEach(link=>link.removeAttribute('aria-current'));
    const matches=link=>{
      const url=new URL(link.href,location.href);
      if(url.hash)return false;
      const path=normalizePath(url.pathname);
      return path===currentPath||(path.endsWith('/articles')&&currentPath.startsWith(path+'/'));
    };
    nav.querySelectorAll(':scope > a').forEach(link=>{
      const isCurrent=matches(link);
      link.hidden=isCurrent;
      if(isCurrent)link.setAttribute('aria-current','page');
    });
    const tools=nav.querySelector(':scope > .nav-tools');
    if(tools){
      const currentTool=Array.from(tools.querySelectorAll('.nav-tools-menu a')).find(matches);
      if(currentTool){
        currentTool.setAttribute('aria-current','page');
        tools.classList.add('is-current');
      }else{
        tools.classList.remove('is-current');
      }
    }
  });
})();
