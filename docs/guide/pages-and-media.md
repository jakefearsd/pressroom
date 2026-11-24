# Pages and media

Pages are for writing that is not part of the dated stream of posts: About,
Contact, a landing page. Media files are the images and other uploads you use
in posts and pages.

## Pages

Open **Pages** to see the weblog's pages, each with its title, slug, status,
whether it shows in the navigation, and its position.

### Add or edit a page

1. Choose **New page**, or click an existing page.
2. Write the **Title** and the text. The page editor is the same as the post
   editor, including preview, shortcodes and the **SEO and sharing** drawer.
3. Set the **Slug**, the address segment the page is served at:
   `https://your-site/handle/slug`.
4. Set **Status** to Draft or Published, and save.

The slug may not be blank, may not contain a slash, and may not be one the
application itself uses. A slug already used by another page is refused. A page
in the trash still holds its slug until you restore or delete it.

If you rename a slug on a published page, Pressroom adds a permanent redirect
from the old address to the new one, so existing links keep working.

Under **Navigation** in the editor rail:

- **Show in navigation** adds the page to the blog's menu.
- **Nav order**: lower numbers appear first.

### Page order

On the **Pages** list, **Move up** and **Move down** reorder the rows. Choose
**Save order** to keep the new order ("Page order saved."). The order controls
where pages appear in the navigation.

### A page as the blog's home page

By default a blog's address shows the list of posts. To show a page there
instead, open **Settings** and choose a published page in **Home page**. The
posts then list at `/posts` under the blog. The Pages list marks the chosen
page **Home page**.

Unpublishing or deleting that page returns the blog's root to the post list.
Publishing it again does not set it as home page again; choose it in
**Settings**.

### Page revisions and trash

- The page editor has a **Revisions** box that works like the one for posts:
  every save that changes the title or text keeps the version it replaced,
  with **Compare** and **Restore**. A restore never changes the page's status
  or address.
- Deleting a page (**Move to the trash**) sends it to the page trash, reached by
  the **Trash (n)** link on the Pages screen. **Restore** brings it back as a
  draft; **Delete forever** removes it. The trash screen states how many days
  trashed pages are kept (7 unless the administrator changed it).
- A page used as a strand keeps its posts attached when trashed; see
  [Writing](writing.md#order-pins-and-strands).

### Site pages

Site admins also have a **Site** section on the Main menu with **Pages**,
**Media** and **Theme** for pages that belong to the whole site, such as a
front door or the privacy policy. They are served directly under the site
address (`https://your-site/privacy`). A published site page's slug cannot be
used as a weblog handle. See [Site administration](site-administration.md).

## Media files

**Media files** is your library. You can arrange files in folders, rename and
move them freely: moving or renaming a file never breaks a URL already pointing
at it.

### Upload

1. Choose **Add media files**.
2. Drag files onto the drop area, or click it to choose them.
3. Optionally set **Tags (space separated)**, a **Copyright statement** and a
   **Folder**. These apply to every file in the upload.
4. Choose **Upload**.

The success page shows each file's name, size, type and link, and offers **New
entry with these** and **Upload more**.

You can also paste or drop an image into the editor; it uploads and inserts an
`[image]` shortcode for you.

Uploads can be switched off by the administrator, and the site sets which file
types are allowed, the maximum file size and the maximum folder size. If an
upload is refused, one of those is usually why.

### Folders

- **New folder** creates one; **Folder name**, then **Create**.
- Pick a folder in **Folder** to browse it.
- **Delete folder** removes a folder and everything in it, after a
  confirmation.
- In a folder, the **Privacy** box has a checkbox: **Private directory:
  hidden from public pages, feeds and galleries**. Use it for files that
  should not be public. Private folders cannot be used for a featured image
  or social share image, and `[gallery]` refuses them.

### Find files

**Search** filters by name, **Type** (**Image**, **Video**, **Audio**,
**Others**), size (**Larger than**, in KB or MB), and **Tags**. **Reset**
clears the filters. Files with no description show "no alt text" in the list.

### Edit a file

Click a file to open **Edit media file**:

- **Alt text**: the description read aloud by screen readers and shown when
  an image cannot load. Fill it in for every image.
- **Tags**, **Copyright statement**, **Folder**.
- **Focal point**: click the part of the image that matters most. Themes keep
  it in view when they crop the image to fit. From the keyboard, focus the
  image and use the arrow keys. **Clear focal point** removes it.
- **Crop image**: **Apply crop** permanently replaces the original and rebuilds
  every smaller version. It cannot be undone.
- **URL**, **Shortcode** and **Thumbnail**: copy the shortcode into a post to
  place the image.

### Bulk actions

Tick files to show the selection bar: **Delete selected**, **Move selected
to**, **New entry with selected**. Deleting media is permanent; there is no
media trash.

### Renditions and photos

For JPEG and PNG files Pressroom makes smaller copies at 480, 960, 1600 and
2400 pixels wide (never enlarging a small image) and serves the best fit to each
visitor's screen. It also makes WebP copies when the server supports it. The
original file is never altered, except by **Crop image**.

By default Pressroom strips GPS location from uploaded photos. The
administrator can turn that off with **Strip GPS location data from uploaded
photos** in **Configuration**.

If images look wrong after an upgrade, a site admin can rebuild the copies with
**Regenerate image renditions** on the **Maintenance** screen.
