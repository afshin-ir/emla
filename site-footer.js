(()=>{
  const contactHref='/emla/contact.html';
  const nav=document.getElementById('primary-navigation')
    ||document.querySelector('header .top-actions,header .topnav,header .site-links,header .toplinks');
  const header=document.querySelector('header.topbar,header.top');
  const host=nav||header;

  if(host){
    let contact=host.querySelector('.nav-contact');
    if(!contact){
      contact=document.createElement('a');
      contact.className='site-nav-button nav-contact';
      contact.href=contactHref;
      contact.textContent='تماس با ما';
    }
    if(location.pathname.endsWith('/contact.html')) contact.setAttribute('aria-current','page');
    else contact.removeAttribute('aria-current');
    const afra=host.querySelector('.nav-afra');
    if(afra&&afra.parentElement===host){
      if(afra.nextElementSibling!==contact) afra.insertAdjacentElement('afterend',contact);
    }else if(contact.parentElement!==host){
      host.appendChild(contact);
    }
  }

  let footer=document.querySelector('.site-footer');
  if(!footer){
    footer=document.createElement('footer');
    footer.className='site-footer';
    document.body.appendChild(footer);
  }
  let note=footer.querySelector('.site-credit')||document.querySelector('.site-credit');
  if(!note){
    note=document.createElement('p');
    footer.appendChild(note);
  }else if(note.parentElement!==footer){
    note.remove();
    footer.appendChild(note);
  }
  note.className='site-credit';
  note.textContent='طراحی سایت و توسعهٔ اپلیکیشن اَفرا توسط: افشین فلاطونی © ۱۴۰۵';

  if(!document.getElementById('site-footer-style')){
    const style=document.createElement('style');
    style.id='site-footer-style';
    style.textContent='.site-footer{max-width:1440px;margin:22px auto 0;padding:0 24px 16px;display:flex;flex-direction:column;align-items:center;gap:8px}.site-credit{width:100%;margin:0;padding:12px 8px 0;border-top:1px solid #e1e9e4;text-align:center;color:#89958e;font-size:.72rem;line-height:1.8;opacity:.82}.dark .site-credit{border-color:#34483e;color:#9aa8a0}@media(max-width:580px){.site-footer{padding:0 13px 14px}.site-credit{font-size:.68rem}}';
    document.head.appendChild(style);
  }
})();